package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.stream2song.backend.client.TwitchClipMediaDownloader;
import com.stream2song.backend.dto.TwitchClipMediaResponse;

class ClipTranscriptionServiceTest {
    private final AtomicReference<String> mediaUrl = new AtomicReference<>();
    private final AtomicInteger mediaCalls = new AtomicInteger();
    private final TwitchClipMediaService mediaService = new TwitchClipMediaService(() -> "broadcaster-1", null) {
        @Override public TwitchClipMediaResponse downloadUrls(String clipId) {
            mediaCalls.incrementAndGet();
            return new TwitchClipMediaResponse(clipId, mediaUrl.get(), null);
        }
        @Override public TwitchClipMediaResponse downloadUrls(String clipId,
                TwitchBroadcasterProvider.ConnectionScope expectedScope) {
            if (!"broadcaster-1".equals(expectedScope.broadcasterId())) {
                throw new ClipMediaFailure(HttpStatus.CONFLICT, "twitch_account_changed");
            }
            return downloadUrls(clipId);
        }
    };

    @Test
    void returnsProviderIndependentTranscriptWithTimedSegments() {
        twitchReturnsMedia("https://clips-media-assets2.twitch.tv/clip.mp4");
        ClipMediaDownloader downloader = uri -> new ClipMediaDownloader.DownloadedMedia(new byte[] {1, 2}, "video/mp4");
        ClipTranscriptionProvider provider = (bytes, type) -> new ClipTranscriptionProvider.Transcript("Hello there.",
                List.of(new ClipTranscriptionProvider.Segment(0.2, 1.4, "Hello there.")));
        var response = new ClipTranscriptionService(mediaService, downloader, provider).transcribe("clip-1");
        assertEquals("Hello there.", response.text());
        assertEquals(0.2, response.segments().getFirst().startSeconds());
        assertEquals(1.4, response.segments().getFirst().endSeconds());
        assertEquals("Hello there.", response.segments().getFirst().text());
        assertEquals(1, mediaCalls.get());
    }

    @Test
    void unavailableClipMediaProducesSafeNotFound() {
        twitchReturnsMedia(null);
        var service = new ClipTranscriptionService(mediaService, uri -> fail("must not fetch"),
                (bytes, type) -> fail("must not transcribe"));
        var failure = assertThrows(ClipTranscriptionService.TranscriptionFailure.class,
                () -> service.transcribe("clip-1"));
        assertEquals(HttpStatus.NOT_FOUND, failure.status());
        assertEquals("twitch_clip_media_unavailable", failure.reason());
    }

    @Test
    void mapsExpiredAndTimedOutMediaFetchesToSafeResponses() {
        twitchReturnsMedia("https://clips-media-assets2.twitch.tv/clip.mp4");
        assertDownloadFailure("clip_media_expired", 404);
        assertDownloadFailure("clip_media_timeout", 504);
    }

    @Test
    void mapsUnsupportedAndOversizedMediaToSafeResponses() {
        twitchReturnsMedia("https://clips-media-assets2.twitch.tv/clip.mp4");
        assertDownloadFailure("unsupported_clip_media_type", 415);
        assertDownloadFailure("clip_media_too_large", 413);
    }

    @Test
    void mapsProviderFailureWithoutExposingProviderDetails() {
        twitchReturnsMedia("https://clips-media-assets2.twitch.tv/clip.mp4");
        var service = new ClipTranscriptionService(mediaService,
                uri -> new ClipMediaDownloader.DownloadedMedia(new byte[] {1}, "video/mp4"),
                (bytes, type) -> { throw new DeepgramClipTranscriptionProvider.TranscriptionProviderFailure("transcription_provider_failed", 502); });
        var failure = assertThrows(ClipTranscriptionService.TranscriptionFailure.class,
                () -> service.transcribe("clip-1"));
        assertEquals(HttpStatus.BAD_GATEWAY, failure.status());
        assertEquals("transcription_provider_failed", failure.reason());
        assertFalse(failure.toString().contains("apiKey"));
    }

    private void assertDownloadFailure(String reason, int status) {
        var service = new ClipTranscriptionService(mediaService,
                uri -> { throw new TwitchClipMediaDownloader.DownloadFailure(reason, status); },
                (bytes, type) -> fail("must not transcribe"));
        var failure = assertThrows(ClipTranscriptionService.TranscriptionFailure.class,
                () -> service.transcribe("clip-1"));
        assertEquals(HttpStatus.valueOf(status), failure.status());
        assertEquals(reason, failure.reason());
    }

    private void twitchReturnsMedia(String url) {
        mediaUrl.set(url);
        mediaCalls.set(0);
    }
}
