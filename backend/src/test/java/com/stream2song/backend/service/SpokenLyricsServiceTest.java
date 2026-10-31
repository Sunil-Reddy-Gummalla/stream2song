package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.stream2song.backend.dto.ClipLyricsResponse;

class SpokenLyricsServiceTest {
    private static final byte[] WAV = OpenAiRealtimeSpokenLyricsProvider.toWav(new byte[]{1,0,2,0});

    @Test void sharesOneSuccessfulGenerationCachesCopiesAndExpiresByTtl() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        SpokenLyricsProvider provider = provider(() -> {
            calls.incrementAndGet(); entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return WAV;
        });
        MutableClock clock = new MutableClock(Instant.parse("2026-10-30T00:00:00Z"));
        SpokenLyricsService service = fixture.service(provider, clock, 4, 1000, 3, Duration.ofMinutes(10));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> service.preview("clip-1", "lyrics-1"));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var two = pool.submit(() -> service.preview("clip-1", "lyrics-1"));
            waitForWaiters(service, 1);
            release.countDown();
            byte[] resultOne = one.get(2, TimeUnit.SECONDS), resultTwo = two.get(2, TimeUnit.SECONDS);
            assertArrayEquals(WAV, resultOne); assertArrayEquals(WAV, resultTwo); assertEquals(1, calls.get());
            resultOne[0] = 0;
            assertArrayEquals(WAV, service.preview("clip-1", "lyrics-1"));
            assertEquals(1, calls.get());
            clock.advance(Duration.ofMinutes(11));
            service.preview("clip-1", "lyrics-1");
            assertEquals(2, calls.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void failedGenerationIsNotCachedAndSuccessfulResultsAreBoundedByTotalAudioBytes() {
        Fixture fixture = new Fixture(); AtomicInteger calls = new AtomicInteger();
        SpokenLyricsProvider failsOnce = provider(() -> {
            if (calls.incrementAndGet() == 1) throw new SpokenLyricsFailure(502, "spoken_preview_provider_failed");
            return WAV;
        });
        SpokenLyricsService retryService = fixture.service(failsOnce, fixture.clock, 4, 1000, 3, Duration.ofMinutes(10));
        assertEquals("spoken_preview_provider_failed", assertThrows(SpokenLyricsFailure.class,
                () -> retryService.preview("clip-1", "lyrics-1")).reason());
        assertArrayEquals(WAV, retryService.preview("clip-1", "lyrics-1"));
        assertEquals(2, calls.get());

        Fixture boundedFixture = new Fixture(); AtomicInteger boundedCalls = new AtomicInteger();
        SpokenLyricsService bounded = boundedFixture.service(provider(() -> { boundedCalls.incrementAndGet(); return WAV; }),
                boundedFixture.clock, 5, 60, 3, Duration.ofMinutes(10));
        boundedFixture.add("clip-2", "lyrics-2"); boundedFixture.add("clip-3", "lyrics-3");
        bounded.preview("clip-1", "lyrics-1"); bounded.preview("clip-2", "lyrics-2"); bounded.preview("clip-3", "lyrics-3");
        assertArrayEquals(WAV, bounded.preview("clip-1", "lyrics-1"));
        assertEquals(4, boundedCalls.get());

        Fixture entryFixture = new Fixture(); AtomicInteger entryCalls = new AtomicInteger();
        SpokenLyricsService entryBounded = entryFixture.service(provider(() -> { entryCalls.incrementAndGet(); return WAV; }),
                entryFixture.clock, 2, 1000, 3, Duration.ofMinutes(10));
        entryFixture.add("clip-2", "lyrics-2"); entryFixture.add("clip-3", "lyrics-3");
        entryBounded.preview("clip-1", "lyrics-1"); entryBounded.preview("clip-2", "lyrics-2"); entryBounded.preview("clip-3", "lyrics-3");
        assertEquals(2, entryBounded.cacheEntryCount());
        entryBounded.preview("clip-1", "lyrics-1");
        assertEquals(4, entryCalls.get());
    }

    @Test void rechecksOwnerAndSnapshotAfterGenerationAndOnEveryCacheHit() throws Exception {
        Fixture fixture = new Fixture(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        SpokenLyricsService service = fixture.service(provider(() -> {
            calls.incrementAndGet(); entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return WAV;
        }), fixture.clock, 8, 2000, 3, Duration.ofMinutes(10));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var pending = pool.submit(() -> service.preview("clip-1", "lyrics-1"));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            fixture.current.set(new TwitchBroadcasterProvider.ConnectionScope("owner", 10));
            fixture.add(fixture.current.get(), "clip-1", "lyrics-1");
            release.countDown();
            ExecutionException stale = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertInstanceOf(SpokenLyricsFailure.class, stale.getCause());
            assertEquals("spoken_preview_lyrics_stale", ((SpokenLyricsFailure) stale.getCause()).reason());
            assertArrayEquals(WAV, service.preview("clip-1", "lyrics-1"));
            assertEquals(2, calls.get());
            fixture.snapshots.remove(fixture.current.get(), "clip-1", "lyrics-1");
            assertEquals("spoken_preview_lyrics_stale", assertThrows(SpokenLyricsFailure.class,
                    () -> service.preview("clip-1", "lyrics-1")).reason());
            assertEquals(2, calls.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void rejectsUnownedAndOverCapacityRequestsWithoutCallingProvider() throws Exception {
        Fixture fixture = new Fixture(); CountDownLatch entered = new CountDownLatch(3), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        SpokenLyricsService service = fixture.service(provider(() -> {
            calls.incrementAndGet(); entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return WAV;
        }), fixture.clock, 8, 2000, 3, Duration.ofMinutes(10));
        fixture.add("clip-2", "lyrics-2"); fixture.add("clip-3", "lyrics-3"); fixture.add("clip-4", "lyrics-4");
        fixture.current.set(new TwitchBroadcasterProvider.ConnectionScope("other-owner", 11));
        assertEquals("spoken_preview_lyrics_stale", assertThrows(SpokenLyricsFailure.class,
                () -> service.preview("clip-1", "lyrics-1")).reason());
        fixture.current.set(fixture.owner);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            var a=pool.submit(()->service.preview("clip-1","lyrics-1"));
            var b=pool.submit(()->service.preview("clip-2","lyrics-2"));
            var c=pool.submit(()->service.preview("clip-3","lyrics-3"));
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            fixture.add("clip-5", "lyrics-5");
            assertEquals("spoken_preview_capacity_exceeded", assertThrows(SpokenLyricsFailure.class,
                    () -> service.preview("clip-5", "lyrics-5")).reason());
            release.countDown(); a.get(2,TimeUnit.SECONDS);b.get(2,TimeUnit.SECONDS);c.get(2,TimeUnit.SECONDS);
            assertEquals(3,calls.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private void waitForWaiters(SpokenLyricsService service,int size)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(System.nanoTime()<end){if(service.waiterCount()==size)return;Thread.sleep(2);}
        fail("expected follower to join the in-flight synthesis");
    }

    private SpokenLyricsProvider provider(java.util.function.Supplier<byte[]> generate) {
        return new SpokenLyricsProvider() {
            @Override public boolean isConfigured() { return true; }
            @Override public String fingerprint() { return "openai:test:marin:v1"; }
            @Override public byte[] synthesize(String lyrics) { return generate.get(); }
        };
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T00:00:00Z"));
        final TwitchBroadcasterProvider.ConnectionScope owner = new TwitchBroadcasterProvider.ConnectionScope("owner", 9);
        final AtomicReference<TwitchBroadcasterProvider.ConnectionScope> current = new AtomicReference<>(owner);
        final TestSnapshots snapshots = new TestSnapshots(clock);
        final TwitchBroadcasterProvider broadcaster = new TwitchBroadcasterProvider() {
            @Override public String connectedBroadcasterId() { return current.get() == null ? null : current.get().broadcasterId(); }
            @Override public ConnectionScope connectionScope() { return current.get(); }
        };
        Fixture() { add("clip-1", "lyrics-1"); }
        void add(String clipId, String lyricsId) { add(owner, clipId, lyricsId); }
        void add(TwitchBroadcasterProvider.ConnectionScope scope, String clipId, String lyricsId) {
            snapshots.values.put(new SnapshotKey(scope, clipId, lyricsId), new MomentAnalysisSnapshotService.StoredLyrics(
                    lyrics(clipId, lyricsId), clock.instant().plus(Duration.ofMinutes(25))));
        }
        SpokenLyricsService service(SpokenLyricsProvider provider, Clock clock, int entries, int bytes, int active, Duration ttl) {
            return new SpokenLyricsService(broadcaster, snapshots, provider, clock, entries, bytes, active, ttl, Duration.ofSeconds(5));
        }
    }

    private static ClipLyricsResponse lyrics(String clipId, String lyricsId) {
        return new ClipLyricsResponse(lyricsId,"analysis-1","interpretation-1",clipId,"pop","playful","medium",true,true,
                "unknown",null,"No verified chat timeline","Song",List.of(new ClipLyricsResponse.Section("verse","Verse",
                        List.of("spoken sample line"),List.of()),new ClipLyricsResponse.Section("chorus","Chorus",List.of("spoken refrain"),List.of())));
    }

    private record SnapshotKey(TwitchBroadcasterProvider.ConnectionScope scope, String clipId, String lyricsId) { }
    private static final class TestSnapshots extends MomentAnalysisSnapshotService {
        final Map<SnapshotKey,StoredLyrics> values = new HashMap<>();
        TestSnapshots(Clock clock) { super(clock); }
        @Override public synchronized StoredLyrics getLyrics(TwitchBroadcasterProvider.ConnectionScope scope,String clipId,String lyricsId) {
            return values.get(new SnapshotKey(scope,clipId,lyricsId));
        }
        synchronized void remove(TwitchBroadcasterProvider.ConnectionScope scope,String clipId,String lyricsId){values.remove(new SnapshotKey(scope,clipId,lyricsId));}
    }

    private static final class MutableClock extends Clock {
        private Instant now; MutableClock(Instant now){this.now=now;}void advance(Duration duration){now=now.plus(duration);}
        @Override public ZoneId getZone(){return ZoneId.of("UTC");}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
}
