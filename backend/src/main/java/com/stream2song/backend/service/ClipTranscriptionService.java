package com.stream2song.backend.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.stream2song.backend.client.TwitchClipMediaDownloader;
import com.stream2song.backend.config.TranscriptionCacheProperties;
import com.stream2song.backend.dto.ClipTranscriptionResponse;
import com.stream2song.backend.service.TwitchBroadcasterProvider.ConnectionScope;

@Service
public class ClipTranscriptionService {
    private final TwitchClipMediaService mediaService;
    private final ClipMediaDownloader downloader;
    private final ClipTranscriptionProvider provider;
    private final TwitchBroadcasterProvider broadcasterProvider;
    private final TranscriptionCacheProperties cacheProperties;
    private final Clock clock;
    private final Map<CacheKey, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<CacheKey, Flight> inFlight = new LinkedHashMap<>();

    @Autowired
    public ClipTranscriptionService(TwitchClipMediaService mediaService, ClipMediaDownloader downloader,
            ClipTranscriptionProvider provider, TwitchBroadcasterProvider broadcasterProvider,
            TranscriptionCacheProperties cacheProperties) {
        this.mediaService = mediaService;
        this.downloader = downloader;
        this.provider = provider;
        this.broadcasterProvider = broadcasterProvider;
        this.cacheProperties = cacheProperties;
        this.clock = Clock.systemUTC();
    }

    public ClipTranscriptionService(TwitchClipMediaService mediaService, ClipMediaDownloader downloader,
            ClipTranscriptionProvider provider) {
        this(mediaService, downloader, provider,
                new TwitchBroadcasterProvider() {
                    @Override public String connectedBroadcasterId() {
                        ConnectionScope scope = connectionScope();
                        return scope == null ? null : scope.broadcasterId();
                    }
                    @Override public ConnectionScope connectionScope() {
                        return mediaService == null ? null : mediaService.connectionScope();
                    }
                },
                new TranscriptionCacheProperties());
    }

    public ClipTranscriptionService(TwitchClipMediaService mediaService, ClipMediaDownloader downloader,
            ClipTranscriptionProvider provider, TwitchBroadcasterProvider broadcasterProvider,
            TranscriptionCacheProperties cacheProperties, Clock clock) {
        this.mediaService = mediaService;
        this.downloader = downloader;
        this.provider = provider;
        this.broadcasterProvider = broadcasterProvider;
        this.cacheProperties = cacheProperties;
        this.clock = clock;
    }

    public ClipTranscriptionResponse transcribe(String clipId) {
        return transcribe(clipId, false);
    }

    public ClipTranscriptionResponse transcribe(String clipId, boolean refresh) {
        return transcribe(clipId, refresh, () -> { });
    }

    public ClipTranscriptionResponse transcribe(String clipId, boolean refresh, Runnable onRefreshSuccess) {
        return transcribe(clipId, refresh, ignored -> onRefreshSuccess.run());
    }

    public ClipTranscriptionResponse transcribe(String clipId, boolean refresh, Consumer<String> onRefreshSuccess) {
        if (clipId == null || !clipId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new TranscriptionFailure(HttpStatus.BAD_REQUEST, "invalid_clip_id");
        }
        ConnectionScope connectionScope = broadcasterProvider.connectionScope();
        if (connectionScope == null || connectionScope.broadcasterId() == null || connectionScope.broadcasterId().isBlank()) {
            throw new TranscriptionFailure(HttpStatus.CONFLICT, "twitch_account_not_connected");
        }
        return transcribeScoped(clipId, refresh, onRefreshSuccess, connectionScope);
    }

    private ClipTranscriptionResponse transcribeScoped(String clipId, boolean refresh,
            Consumer<String> onRefreshSuccess, ConnectionScope connectionScope) {
        String broadcasterId = connectionScope.broadcasterId();
        if (!provider.isConfigured()) {
            throw new TranscriptionFailure(HttpStatus.SERVICE_UNAVAILABLE, "transcription_provider_not_configured");
        }
        CacheKey key = new CacheKey(broadcasterId, connectionScope.generation(), clipId, provider.cacheFingerprint());
        CompletableFuture<ClipTranscriptionResponse> future;
        boolean load = false;
        boolean waitThenRefresh = false;
        synchronized (cache) {
            removeExpired();
            CacheEntry entry = cache.get(key);
            Flight currentFlight = inFlight.get(key);
            if (currentFlight != null) {
                future = currentFlight.future();
                waitThenRefresh = refresh && !currentFlight.refresh();
            } else if (!refresh && entry != null) {
                requireCurrentScope(connectionScope);
                return entry.response();
            } else {
                if (inFlight.size() >= 32) {
                    throw new TranscriptionFailure(HttpStatus.SERVICE_UNAVAILABLE, "transcription_capacity_exceeded");
                }
                future = new CompletableFuture<>();
                inFlight.put(key, new Flight(future, refresh));
                load = true;
            }
        }
        if (!load) {
            ClipTranscriptionResponse shared;
            try { shared = await(future); }
            catch (RuntimeException failure) {
                if (waitThenRefresh) {
                    requireCurrentScope(connectionScope);
                    return transcribeScoped(clipId, true, onRefreshSuccess, connectionScope);
                }
                if (!refresh) {
                    synchronized (cache) {
                        removeExpired();
                        CacheEntry previous = cache.get(key);
                        if (previous != null) {
                            requireCurrentScope(connectionScope);
                            return previous.response();
                        }
                    }
                }
                throw failure;
            }
            requireCurrentScope(connectionScope);
            return waitThenRefresh ? transcribeScoped(clipId, true, onRefreshSuccess, connectionScope) : shared;
        }
        try {
            ClipTranscriptionResponse response = load(clipId, connectionScope);
            synchronized (cache) {
                requireCurrentScope(connectionScope);
                if (refresh) onRefreshSuccess.accept(broadcasterId);
                requireCurrentScope(connectionScope);
                cache.put(key, new CacheEntry(response, clock.instant().plusSeconds(cacheProperties.ttlSeconds())));
                while (cache.size() > cacheProperties.maxEntries()) cache.remove(cache.keySet().iterator().next());
                future.complete(response);
                inFlight.remove(key);
            }
            return response;
        } catch (RuntimeException failure) {
            synchronized (cache) { inFlight.remove(key); }
            future.completeExceptionally(failure);
            throw failure;
        }
    }

    private ClipTranscriptionResponse load(String clipId, ConnectionScope connectionScope) {
        try {
            requireCurrentScope(connectionScope);
            var urls = mediaService.downloadUrls(clipId, connectionScope);
            requireCurrentScope(connectionScope);
            String url = urls.landscapeDownloadUrl() != null ? urls.landscapeDownloadUrl() : urls.portraitDownloadUrl();
            if (url == null || url.isBlank()) throw new TranscriptionFailure(HttpStatus.NOT_FOUND, "twitch_clip_media_unavailable");
            ClipMediaDownloader.DownloadedMedia media = downloader.download(URI.create(url));
            requireCurrentScope(connectionScope);
            ClipTranscriptionProvider.Transcript transcript = provider.transcribe(media.bytes(), media.contentType());
            requireCurrentScope(connectionScope);
            if (transcript.segments() != null && transcript.segments().size() > 1000) {
                throw new TranscriptionFailure(HttpStatus.BAD_GATEWAY, "transcription_result_too_large");
            }
            List<ClipTranscriptionResponse.TimedTranscriptSegment> segments = transcript.segments() == null ? List.of()
                    : transcript.segments().stream().map(segment -> new ClipTranscriptionResponse.TimedTranscriptSegment(
                            segment.startSeconds(), segment.endSeconds(), segment.text())).toList();
            String text = transcript.text() == null ? "" : transcript.text();
            long bytes = text.getBytes(StandardCharsets.UTF_8).length + segments.stream()
                    .mapToLong(segment -> segment.text() == null ? 0 : segment.text().getBytes(StandardCharsets.UTF_8).length).sum();
            if (text.length() > 250_000 || segments.size() > 1000 || bytes > 1_000_000
                    || segments.stream().anyMatch(segment -> segment.text() != null && segment.text().length() > 4_000)) {
                throw new TranscriptionFailure(HttpStatus.BAD_GATEWAY, "transcription_result_too_large");
            }
            return new ClipTranscriptionResponse(clipId, text, List.copyOf(segments));
        } catch (TwitchClipMediaService.ClipMediaFailure failure) {
            throw new TranscriptionFailure(failure.status(), failure.reason());
        } catch (TwitchClipMediaDownloader.DownloadFailure failure) {
            throw new TranscriptionFailure(HttpStatusCode.status(failure.status()), failure.reason());
        } catch (DeepgramClipTranscriptionProvider.TranscriptionProviderFailure failure) {
            throw new TranscriptionFailure(HttpStatusCode.status(failure.status()), failure.reason());
        } catch (IllegalArgumentException failure) {
            throw new TranscriptionFailure(HttpStatus.BAD_GATEWAY, "clip_media_url_rejected");
        }
    }

    private ClipTranscriptionResponse await(CompletableFuture<ClipTranscriptionResponse> future) {
        try { return future.join(); }
        catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new TranscriptionFailure(HttpStatus.BAD_GATEWAY, "transcription_provider_failed");
        }
    }

    private void removeExpired() {
        Instant now = clock.instant();
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private void requireCurrentScope(ConnectionScope expected) {
        if (!broadcasterProvider.isCurrentConnection(expected)) {
            throw new TranscriptionFailure(HttpStatus.CONFLICT, "twitch_account_changed");
        }
    }

    private record CacheKey(String broadcasterId, long connectionGeneration, String clipId, String providerFingerprint) { }
    private record CacheEntry(ClipTranscriptionResponse response, Instant expiresAt) { }
    private record Flight(CompletableFuture<ClipTranscriptionResponse> future, boolean refresh) { }

    private static final class HttpStatusCode {
        static HttpStatus status(int status) {
            HttpStatus resolved = HttpStatus.resolve(status);
            return resolved == null ? HttpStatus.BAD_GATEWAY : resolved;
        }
    }

    public static class TranscriptionFailure extends RuntimeException {
        private final HttpStatus status;
        private final String reason;
        public TranscriptionFailure(HttpStatus status, String reason) { super(reason); this.status = status; this.reason = reason; }
        public HttpStatus status() { return status; }
        public String reason() { return reason; }
    }
}
