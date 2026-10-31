package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.stream2song.backend.config.TranscriptionCacheProperties;
import com.stream2song.backend.dto.ClipTranscriptionResponse;
import com.stream2song.backend.dto.TwitchClipMediaResponse;

class ClipTranscriptionCacheTest {
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    @Test
    void cachesSuccessfulResponsesByAccountClipAndProviderFingerprintAndExpiresByClock() {
        MutableClock clock = new MutableClock(NOW);
        AtomicReference<String> account = new AtomicReference<>("account-a");
        AtomicInteger loads = new AtomicInteger();
        ClipTranscriptionService service = service(account, clock, provider("model-a", loads, () -> "cached"));
        assertEquals("cached", service.transcribe("clip-1").text());
        service.transcribe("clip-1");
        assertEquals(1, loads.get());

        account.set("account-b");
        service.transcribe("clip-1");
        assertEquals(2, loads.get());
        account.set("account-a");
        service.transcribe("clip-2");
        assertEquals(3, loads.get());

        ClipTranscriptionService otherProvider = service(account, clock, provider("model-b", loads, () -> "other"));
        assertEquals("other", otherProvider.transcribe("clip-1").text());
        assertEquals(4, loads.get());

        clock.advance(Duration.ofSeconds(61));
        service.transcribe("clip-1");
        assertEquals(5, loads.get());
    }

    @Test void providerFingerprintAndEntryCapacityArePartOfCacheKeyAndBound() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> fingerprint = new AtomicReference<>("model-a");
        ClipTranscriptionProvider provider = new ClipTranscriptionProvider() {
            @Override public String cacheFingerprint() { return fingerprint.get(); }
            @Override public Transcript transcribe(byte[] media, String contentType) {
                return new Transcript("load-" + calls.incrementAndGet(), List.of());
            }
        };
        var account = new AtomicReference<>("owner");
        var clock = new MutableClock(NOW);
        ClipTranscriptionService service = service(account, clock, provider, new TranscriptionCacheProperties(600, 1));
        assertEquals("load-1", service.transcribe("clip-a").text());
        fingerprint.set("model-b");
        assertEquals("load-2", service.transcribe("clip-a").text());
        assertEquals("load-3", service.transcribe("clip-b").text());
        assertEquals("load-4", service.transcribe("clip-a").text());
        assertEquals(4, calls.get());
        assertEquals(86_400, new TranscriptionCacheProperties(999_999, 500).ttlSeconds());
        assertEquals(100, new TranscriptionCacheProperties(1800, 500).maxEntries());
    }

    @Test void rejectsOversizedTranscriptInsteadOfTruncatingOrCachingIt() {
        AtomicInteger calls = new AtomicInteger();
        ClipTranscriptionProvider provider = (bytes, type) -> {
            calls.incrementAndGet();
            return new ClipTranscriptionProvider.Transcript("text", java.util.stream.IntStream.range(0, 1001)
                    .mapToObj(i -> new ClipTranscriptionProvider.Segment(0, 1, "segment")).toList());
        };
        ClipTranscriptionService service = service(new AtomicReference<>("owner"), new MutableClock(NOW), provider);
        for (int i = 0; i < 2; i++) {
            var failure = assertThrows(ClipTranscriptionService.TranscriptionFailure.class, () -> service.transcribe("clip"));
            assertEquals("transcription_result_too_large", failure.reason());
        }
        assertEquals(2, calls.get());

        AtomicInteger textCalls = new AtomicInteger();
        ClipTranscriptionService largeText = service(new AtomicReference<>("owner"), new MutableClock(NOW),
                (bytes, type) -> {
                    textCalls.incrementAndGet();
                    return new ClipTranscriptionProvider.Transcript("x".repeat(250_001), List.of());
                });
        var textFailure = assertThrows(ClipTranscriptionService.TranscriptionFailure.class,
                () -> largeText.transcribe("clip"));
        assertEquals("transcription_result_too_large", textFailure.reason());
        assertEquals(1, textCalls.get());
    }

    @Test
    void doesNotCacheFailuresAndFailedRefreshRetainsPreviousSuccess() {
        AtomicInteger calls = new AtomicInteger();
        ClipTranscriptionProvider provider = new ClipTranscriptionProvider() {
            @Override public String cacheFingerprint() { return "retry-provider-v1"; }
            @Override public Transcript transcribe(byte[] media, String contentType) {
                int call = calls.incrementAndGet();
                if (call == 1 || call == 3) throw new DeepgramClipTranscriptionProvider.TranscriptionProviderFailure("transcription_provider_failed", 502);
                return new Transcript("good", List.of());
            }
        };
        ClipTranscriptionService service = service(new AtomicReference<>("account"), new MutableClock(NOW), provider);
        assertThrows(ClipTranscriptionService.TranscriptionFailure.class, () -> service.transcribe("clip"));
        assertEquals("good", service.transcribe("clip").text());
        assertThrows(ClipTranscriptionService.TranscriptionFailure.class, () -> service.transcribe("clip", true));
        assertEquals("good", service.transcribe("clip").text());
        assertEquals(3, calls.get());
    }

    @Test
    void coalescesConcurrentSameKeyCallsAndConcurrentRefreshInvalidatesOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger invalidations = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ClipTranscriptionProvider provider = new ClipTranscriptionProvider() {
            @Override public String cacheFingerprint() { return "single-flight"; }
            @Override public Transcript transcribe(byte[] media, String contentType) {
                int call = calls.incrementAndGet();
                if (call > 1) {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                }
                return new Transcript("v" + call, List.of());
            }
        };
        ClipTranscriptionService service = service(new AtomicReference<>("account"), new MutableClock(NOW), provider);
        assertEquals("v1", service.transcribe("clip").text());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            var a = pool.submit(() -> service.transcribe("clip", true, ignored -> invalidations.incrementAndGet()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var b = pool.submit(() -> service.transcribe("clip", true, ignored -> invalidations.incrementAndGet()));
            var c = pool.submit(() -> service.transcribe("clip", true, ignored -> invalidations.incrementAndGet()));
            Thread.sleep(100);
            release.countDown();
            assertEquals("v2", a.get(5, TimeUnit.SECONDS).text());
            assertEquals("v2", b.get(5, TimeUnit.SECONDS).text());
            assertEquals("v2", c.get(5, TimeUnit.SECONDS).text());
            assertEquals(2, calls.get());
            assertEquals(1, invalidations.get());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void successfulRefreshPublishesNewValueAndRunsInvalidationOnce() {
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger invalidations = new AtomicInteger();
        ClipTranscriptionService service = service(new AtomicReference<>("account"), new MutableClock(NOW),
                provider("refresh-v1", loads, () -> "v" + loads.get()));
        assertEquals("v1", service.transcribe("clip").text());
        ClipTranscriptionResponse refreshed = service.transcribe("clip", true, ignored -> invalidations.incrementAndGet());
        assertEquals("v2", refreshed.text());
        assertEquals("v2", service.transcribe("clip").text());
        assertEquals(1, invalidations.get());
        assertEquals(2, loads.get());
    }

    @Test void normalRequestDuringRefreshWaitsForAtomicInvalidationAndNewPublication() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ClipTranscriptionProvider provider = new ClipTranscriptionProvider() {
            @Override public Transcript transcribe(byte[] media, String contentType) {
                int call = calls.incrementAndGet();
                if (call == 2) {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                }
                return new Transcript("v" + call, List.of());
            }
        };
        ClipTranscriptionService service = service(new AtomicReference<>("account"), new MutableClock(NOW), provider);
        service.transcribe("clip");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var refresh = pool.submit(() -> service.transcribe("clip", true));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var normal = pool.submit(() -> service.transcribe("clip"));
            Thread.sleep(50);
            release.countDown();
            assertEquals("v2", refresh.get(5, TimeUnit.SECONDS).text());
            assertEquals("v2", normal.get(5, TimeUnit.SECONDS).text());
            assertEquals(2, calls.get());
        } finally { pool.shutdownNow(); }
    }

    @Test void successfulRefreshInvalidatesTheBroadcasterCapturedAtRequestStart() throws Exception {
        AtomicReference<String> account = new AtomicReference<>("owner-a");
        AtomicInteger calls = new AtomicInteger();
        ClipTranscriptionProvider provider = (bytes, type) ->
                new ClipTranscriptionProvider.Transcript("refresh-" + calls.incrementAndGet(), List.of());
        ClipTranscriptionService service = service(account, new MutableClock(NOW), provider);
        AtomicReference<String> invalidatedScope = new AtomicReference<>();
        var failure = assertThrows(ClipTranscriptionService.TranscriptionFailure.class,
                () -> service.transcribe("clip", true, scope -> {
                    invalidatedScope.set(scope);
                    account.set("owner-b");
                }));
        assertEquals("twitch_account_changed", failure.reason());
        assertEquals("owner-a", invalidatedScope.get());
        assertEquals("refresh-2", service.transcribe("clip").text());
        assertEquals("refresh-2", service.transcribe("clip").text());
        assertEquals(2, calls.get());
    }

    @Test void accountSwitchDuringMediaLookupRejectsResultAndDoesNotPoisonCache() {
        assertAccountChangeFailsSafely(false);
    }

    @Test void accountAtoBtoAReauthorizationIsDetectedByConnectionGeneration() {
        assertAccountChangeFailsSafely(true);
    }

    private void assertAccountChangeFailsSafely(boolean switchBackToOriginal) {
        MutableScopeProvider account = new MutableScopeProvider("owner-a", 1);
        AtomicInteger mediaCalls = new AtomicInteger();
        AtomicReference<TwitchBroadcasterProvider.ConnectionScope> mediaScope = new AtomicReference<>();
        AtomicInteger downloaderCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        TwitchClipMediaService media = new TwitchClipMediaService(account, null) {
            @Override public TwitchClipMediaResponse downloadUrls(String clipId,
                    TwitchBroadcasterProvider.ConnectionScope expectedScope) {
                mediaCalls.incrementAndGet();
                mediaScope.set(expectedScope);
                if (mediaCalls.get() == 1) {
                    account.switchTo("owner-b");
                    if (switchBackToOriginal) account.switchTo("owner-a");
                    return new TwitchClipMediaResponse(clipId, "https://example.invalid/owner-b.mp4", null);
                }
                return new TwitchClipMediaResponse(clipId,
                        "https://example.invalid/" + expectedScope.broadcasterId() + ".mp4", null);
            }
        };
        ClipTranscriptionService service = new ClipTranscriptionService(media, uri -> {
            downloaderCalls.incrementAndGet();
            assertTrue(uri.getPath().contains(account.connectedBroadcasterId()));
            return new ClipMediaDownloader.DownloadedMedia(new byte[] {1}, "video/mp4");
        }, (bytes, type) -> {
            providerCalls.incrementAndGet();
            return new ClipTranscriptionProvider.Transcript(account.connectedBroadcasterId() + " transcript", List.of());
        }, account, new TranscriptionCacheProperties(600, 100), new MutableClock(NOW));

        var failure = assertThrows(ClipTranscriptionService.TranscriptionFailure.class,
                () -> service.transcribe("clip"));
        assertEquals("twitch_account_changed", failure.reason());
        assertEquals("owner-a", mediaScope.get().broadcasterId());
        assertEquals(1, mediaScope.get().generation());
        assertEquals(0, downloaderCalls.get());
        assertEquals(0, providerCalls.get());

        String currentOwner = switchBackToOriginal ? "owner-a" : "owner-b";
        var recovered = service.transcribe("clip");
        assertEquals(currentOwner + " transcript", recovered.text());
        assertEquals(currentOwner + " transcript", service.transcribe("clip").text());
        assertEquals(2, mediaCalls.get());
        assertEquals(1, downloaderCalls.get());
        assertEquals(1, providerCalls.get());
    }

    @Test void rejectsMoreThanThirtyTwoDistinctConcurrentLoads() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(32);
        CountDownLatch release = new CountDownLatch(1);
        ClipTranscriptionProvider provider = (bytes, type) -> {
            calls.incrementAndGet(); entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            return new ClipTranscriptionProvider.Transcript("ok", List.of());
        };
        ClipTranscriptionService service = service(new AtomicReference<>("account"), new MutableClock(NOW), provider);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            List<java.util.concurrent.Future<ClipTranscriptionResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 32; i++) {
                String clipId = "clip-" + i;
                futures.add(pool.submit(() -> service.transcribe(clipId)));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var failure = assertThrows(ClipTranscriptionService.TranscriptionFailure.class,
                    () -> service.transcribe("overflow"));
            assertEquals("transcription_capacity_exceeded", failure.reason());
            release.countDown();
            for (var future : futures) assertEquals("ok", future.get(5, TimeUnit.SECONDS).text());
            assertEquals(32, calls.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private ClipTranscriptionProvider provider(String fingerprint, AtomicInteger loads, java.util.function.Supplier<String> text) {
        return new ClipTranscriptionProvider() {
            @Override public String cacheFingerprint() { return fingerprint; }
            @Override public Transcript transcribe(byte[] media, String contentType) {
                loads.incrementAndGet();
                return new Transcript(text.get(), List.of());
            }
        };
    }

    private ClipTranscriptionService service(AtomicReference<String> account, Clock clock, ClipTranscriptionProvider provider) {
        return service(account, clock, provider, new TranscriptionCacheProperties(60, 100));
    }

    private ClipTranscriptionService service(AtomicReference<String> account, Clock clock, ClipTranscriptionProvider provider,
            TranscriptionCacheProperties properties) {
        TwitchClipMediaService media = new TwitchClipMediaService(account::get, null) {
            @Override public TwitchClipMediaResponse downloadUrls(String clipId) {
                return new TwitchClipMediaResponse(clipId, "https://example.invalid/media.mp4", null);
            }
            @Override public TwitchClipMediaResponse downloadUrls(String clipId,
                    TwitchBroadcasterProvider.ConnectionScope expectedScope) {
                if (!account.get().equals(expectedScope.broadcasterId())) {
                    throw new TwitchClipMediaService.ClipMediaFailure(org.springframework.http.HttpStatus.CONFLICT,
                            "twitch_account_changed");
                }
                return downloadUrls(clipId);
            }
        };
        return new ClipTranscriptionService(media,
                uri -> new ClipMediaDownloader.DownloadedMedia(new byte[] {1}, "video/mp4"), provider,
                account::get, properties, clock);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration amount) { instant = instant.plus(amount); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static final class MutableScopeProvider implements TwitchBroadcasterProvider {
        private final AtomicReference<ConnectionScope> scope;
        private MutableScopeProvider(String broadcasterId, long generation) {
            scope = new AtomicReference<>(new ConnectionScope(broadcasterId, generation));
        }
        void switchTo(String broadcasterId) {
            scope.updateAndGet(current -> new ConnectionScope(broadcasterId, current.generation() + 1));
        }
        @Override public String connectedBroadcasterId() { return scope.get().broadcasterId(); }
        @Override public ConnectionScope connectionScope() { return scope.get(); }
    }
}
