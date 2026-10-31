package com.stream2song.backend.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.stream2song.backend.dto.ClipLyricsResponse;

@Service
public class SpokenLyricsService {
    static final int MAX_CACHE_ENTRIES = 32;
    static final int MAX_CACHE_BYTES = 64 * 1024 * 1024;
    static final int MAX_ACTIVE_GENERATIONS = 3;
    static final int MAX_WAITERS_PER_GENERATION = 8;
    static final int MAX_WAV_BYTES = 8 * 1024 * 1024;
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final Duration WAIT_TTL = Duration.ofSeconds(65);
    private final TwitchBroadcasterProvider broadcasterProvider;
    private final MomentAnalysisSnapshotService snapshots;
    private final SpokenLyricsProvider provider;
    private final Clock clock;
    private final int maxCacheEntries;
    private final int maxCacheBytes;
    private final int maxActiveGenerations;
    private final Duration cacheTtl;
    private final Duration waitTtl;
    private final LinkedHashMap<Key, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<Key, Flight> flights = new LinkedHashMap<>();
    private int cachedBytes;

    @Autowired
    public SpokenLyricsService(TwitchBroadcasterProvider broadcasterProvider, MomentAnalysisSnapshotService snapshots,
            SpokenLyricsProvider provider) {
        this(broadcasterProvider, snapshots, provider, Clock.systemUTC());
    }

    SpokenLyricsService(TwitchBroadcasterProvider broadcasterProvider, MomentAnalysisSnapshotService snapshots,
            SpokenLyricsProvider provider, Clock clock) {
        this(broadcasterProvider, snapshots, provider, clock, MAX_CACHE_ENTRIES, MAX_CACHE_BYTES,
                MAX_ACTIVE_GENERATIONS, CACHE_TTL, WAIT_TTL);
    }

    SpokenLyricsService(TwitchBroadcasterProvider broadcasterProvider, MomentAnalysisSnapshotService snapshots,
            SpokenLyricsProvider provider, Clock clock, int maxCacheEntries, int maxCacheBytes,
            int maxActiveGenerations, Duration cacheTtl, Duration waitTtl) {
        this.broadcasterProvider = broadcasterProvider;
        this.snapshots = snapshots;
        this.provider = provider;
        this.clock = clock;
        this.maxCacheEntries = maxCacheEntries;
        this.maxCacheBytes = maxCacheBytes;
        this.maxActiveGenerations = maxActiveGenerations;
        this.cacheTtl = cacheTtl;
        this.waitTtl = waitTtl;
    }

    public byte[] preview(String clipId, String lyricsId) {
        if (!validId(clipId) || !validId(lyricsId)) throw failure(400, "spoken_preview_request_invalid");
        TwitchBroadcasterProvider.ConnectionScope scope = broadcasterProvider.connectionScope();
        if (scope == null) throw failure(409, "twitch_account_not_connected");
        MomentAnalysisSnapshotService.StoredLyrics stored = freshLyrics(scope, clipId, lyricsId);
        if (!validStored(stored, clipId, lyricsId)) throw failure(409, "spoken_preview_lyrics_stale");
        if (!provider.isConfigured()) throw failure(503, "spoken_preview_not_configured");
        String text = spokenText(stored.response());
        if (text.isBlank() || text.length() > OpenAiRealtimeSpokenLyricsProvider.MAX_LYRICS_CHARS)
            throw failure(422, "spoken_preview_input_invalid");
        Key key = new Key(scope, clipId, lyricsId, provider.fingerprint());
        CompletableFuture<byte[]> future;
        Flight sharedFlight;
        boolean leader = false;
        synchronized (this) {
            pruneLocked();
            Entry hit = cache.get(key);
            if (hit != null && hit.expiresAt().isAfter(clock.instant())) {
                ensureFresh(scope, clipId, lyricsId);
                return hit.bytes().clone();
            }
            sharedFlight = flights.get(key);
            if (sharedFlight == null) {
                if (flights.size() >= maxActiveGenerations) throw failure(503, "spoken_preview_capacity_exceeded");
                sharedFlight = new Flight();
                flights.put(key, sharedFlight);
                leader = true;
            } else {
                if (sharedFlight.waiters >= MAX_WAITERS_PER_GENERATION) throw failure(503, "spoken_preview_capacity_exceeded");
                sharedFlight.waiters++;
            }
            future = sharedFlight.result;
        }
        if (leader) return generateAndPublish(key, sharedFlight, scope, clipId, lyricsId, text, stored.expiresAt());
        try {
            byte[] result = future.get(waitTtl.toMillis(), TimeUnit.MILLISECONDS);
            ensureFresh(scope, clipId, lyricsId);
            return result.clone();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure(504, "spoken_preview_timeout");
        } catch (TimeoutException timeout) {
            throw failure(504, "spoken_preview_timeout");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SpokenLyricsFailure spokenFailure) throw spokenFailure;
            throw failure(502, "spoken_preview_provider_failed");
        } finally {
            synchronized (this) { sharedFlight.waiters--; }
        }
    }

    private byte[] generateAndPublish(Key key, Flight flight,
            TwitchBroadcasterProvider.ConnectionScope scope, String clipId, String lyricsId, String text, Instant sourceExpiry) {
        try {
            ensureFresh(scope, clipId, lyricsId);
            byte[] wav = provider.synthesize(text);
            if (!validWav(wav)) throw failure(502, "spoken_preview_audio_invalid");
            ensureFresh(scope, clipId, lyricsId);
            Instant expiresAt = min(sourceExpiry, clock.instant().plus(cacheTtl));
            synchronized (this) {
                ensureFresh(scope, clipId, lyricsId);
                if (expiresAt.isAfter(clock.instant())) putLocked(key, wav, expiresAt);
                flight.result.complete(wav.clone());
            }
            return wav.clone();
        } catch (SpokenLyricsFailure failure) {
            flight.result.completeExceptionally(failure);
            throw failure;
        } catch (RuntimeException failure) {
            SpokenLyricsFailure safe = new SpokenLyricsFailure(502, "spoken_preview_provider_failed");
            flight.result.completeExceptionally(safe);
            throw safe;
        } finally {
            synchronized (this) { flights.remove(key, flight); }
        }
    }

    private void ensureFresh(TwitchBroadcasterProvider.ConnectionScope scope, String clipId, String lyricsId) {
        if (!broadcasterProvider.isCurrentConnection(scope) || freshLyrics(scope, clipId, lyricsId) == null)
            throw failure(409, "spoken_preview_lyrics_stale");
    }

    private MomentAnalysisSnapshotService.StoredLyrics freshLyrics(TwitchBroadcasterProvider.ConnectionScope scope,
            String clipId, String lyricsId) {
        MomentAnalysisSnapshotService.StoredLyrics stored = snapshots.getLyrics(scope, clipId, lyricsId);
        return validStored(stored, clipId, lyricsId) ? stored : null;
    }

    private boolean validStored(MomentAnalysisSnapshotService.StoredLyrics stored, String clipId, String lyricsId) {
        return stored != null && stored.response() != null && clipId.equals(stored.response().clipId())
                && lyricsId.equals(stored.response().lyricsId());
    }

    private synchronized void putLocked(Key key, byte[] bytes, Instant expiresAt) {
        Entry old = cache.remove(key);
        if (old != null) cachedBytes -= old.bytes().length;
        if (bytes.length > maxCacheBytes) return;
        while (!cache.isEmpty() && (cache.size() >= maxCacheEntries || cachedBytes + bytes.length > maxCacheBytes)) {
            Entry evicted = cache.remove(cache.keySet().iterator().next());
            cachedBytes -= evicted.bytes().length;
        }
        cache.put(key, new Entry(bytes.clone(), expiresAt));
        cachedBytes += bytes.length;
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void cleanup() { pruneLocked(); }

    synchronized int inFlightCount() { return flights.size(); }
    synchronized int waiterCount() { return flights.values().stream().mapToInt(flight -> flight.waiters).sum(); }
    synchronized int cacheEntryCount() { return cache.size(); }

    private void pruneLocked() {
        Instant now = clock.instant();
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Key key = entry.getKey();
            if (!entry.getValue().expiresAt().isAfter(now)
                    || !broadcasterProvider.isCurrentConnection(key.scope())
                    || snapshots.getLyrics(key.scope(), key.clipId(), key.lyricsId()) == null) {
                cachedBytes -= entry.getValue().bytes().length;
                iterator.remove();
            }
        }
    }

    private String spokenText(ClipLyricsResponse lyrics) {
        StringBuilder text = new StringBuilder();
        for (ClipLyricsResponse.Section section : lyrics.sections()) {
            for (String line : section.lines()) {
                if (text.length() > 0) text.append('\n');
                text.append(line);
            }
        }
        return text.toString();
    }

    private boolean validWav(byte[] bytes) {
        if (bytes == null || bytes.length < 46 || bytes.length > MAX_WAV_BYTES) return false;
        return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E'
                && intLe(bytes, 4) == bytes.length - 8
                && bytes[20] == 1 && bytes[21] == 0 && bytes[22] == 1 && bytes[23] == 0
                && intLe(bytes, 24) == 24_000 && intLe(bytes, 28) == 48_000
                && bytes[32] == 2 && bytes[33] == 0 && bytes[34] == 16 && bytes[35] == 0
                && intLe(bytes, 40) == bytes.length - 44;
    }

    private int intLe(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8)
                | ((bytes[offset + 2] & 255) << 16) | ((bytes[offset + 3] & 255) << 24);
    }

    private Instant min(Instant one, Instant two) { return one.isBefore(two) ? one : two; }
    private boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,100}"); }
    private SpokenLyricsFailure failure(int status, String reason) { return new SpokenLyricsFailure(status, reason); }

    private record Key(TwitchBroadcasterProvider.ConnectionScope scope, String clipId, String lyricsId, String fingerprint) { }
    private record Entry(byte[] bytes, Instant expiresAt) { }
    private static final class Flight { final CompletableFuture<byte[]> result = new CompletableFuture<>(); int waiters; }
}
