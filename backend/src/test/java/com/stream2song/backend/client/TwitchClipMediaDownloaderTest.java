package com.stream2song.backend.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.net.URI;

import org.junit.jupiter.api.Test;

class TwitchClipMediaDownloaderTest {
    @Test
    void allowsOnlySupportedAudioAndVideoContentTypes() {
        assertTrue(TwitchClipMediaDownloader.isSupportedContentType("video/mp4"));
        assertTrue(TwitchClipMediaDownloader.isSupportedContentType("audio/mpeg"));
        assertFalse(TwitchClipMediaDownloader.isSupportedContentType("text/html"));
    }

    @Test
    void sniffsAndNormalizesRecognizedSignaturesUnderUnknownDeclaredTypes() {
        byte[] mp4 = new byte[12];
        mp4[4] = 'f'; mp4[5] = 't'; mp4[6] = 'y'; mp4[7] = 'p';
        assertEquals("video/mp4", TwitchClipMediaDownloader.normalizeContentType("application/octet-stream", mp4));
        assertEquals("video/webm", TwitchClipMediaDownloader.normalizeContentType("application/x-twitch-clip",
                bytes(0x1a, 0x45, 0xdf, 0xa3, 0x00)));
        assertEquals("audio/mpeg", TwitchClipMediaDownloader.normalizeContentType("binary/octet-stream",
                bytes('I', 'D', '3', 4, 0, 0, 0, 0, 0, 0)));
        assertEquals("audio/wav", TwitchClipMediaDownloader.normalizeContentType("application/octet-stream",
                bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E')));
        assertEquals("audio/ogg", TwitchClipMediaDownloader.normalizeContentType("",
                bytes('O', 'g', 'g', 'S', 0)));
        assertEquals("audio/aac", TwitchClipMediaDownloader.normalizeContentType("application/x-binary",
                bytes(0xff, 0xf1, 0x50, 0x80, 0x00, 0xe0, 0x00)));
    }

    @Test
    void keepsUnknownAndTextMimeTypesUnsupportedAndPreservesDirectMimeBehavior() {
        assertEquals("video/mp4", TwitchClipMediaDownloader.normalizeContentType("video/mp4", bytes('n', 'o')));
        assertUnsupported("application/octet-stream", bytes('<', 'h', 't', 'm', 'l'));
        assertUnsupported("text/html", bytes('<', 'h', 't', 'm', 'l'));
        assertUnsupported("application/x-unknown", bytes(0, 1, 2, 3));
    }

    @Test
    void enforcesHardInMemoryBodyLimit() {
        var failure = assertThrows(TwitchClipMediaDownloader.DownloadFailure.class,
                () -> TwitchClipMediaDownloader.readBounded(new ByteArrayInputStream(new byte[11]), 10));
        assertEquals("clip_media_too_large", failure.reason());
        assertEquals(413, failure.status());
    }

    @Test
    void octetStreamBodyStillCannotExceedTheBoundedReaderLimit() {
        var failure = assertThrows(TwitchClipMediaDownloader.DownloadFailure.class,
                () -> TwitchClipMediaDownloader.readBounded(new ByteArrayInputStream(new byte[21]), 20));
        assertEquals("clip_media_too_large", failure.reason());
        assertEquals(413, failure.status());
    }

    @Test
    void rejectsNonTwitchHostsBeforeNetworkAccess() {
        var downloader = new TwitchClipMediaDownloader();
        var failure = assertThrows(TwitchClipMediaDownloader.DownloadFailure.class,
                () -> downloader.download(URI.create("https://example.com/clip.mp4")));
        assertEquals("clip_media_url_rejected", failure.reason());
    }

    @Test
    void acceptsOfficialTwitchDownloadCdnHostWithoutOpeningNetworkConnection() {
        assertDoesNotThrow(() -> TwitchClipMediaDownloader.validateUri(URI.create(
                "https://production.assets.clips.twitchcdn.net/v1/clip.mp4?sig=private-value")));
    }

    @Test
    void acceptsOnlyTheExactDocumentedCloudfrontHostname() {
        assertDoesNotThrow(() -> TwitchClipMediaDownloader.validateUri(URI.create(
                "https://d1ndex63qxojbr.cloudfront.net/clip.mp4?sig=private-value")));
        assertRejected("https://sub.d1ndex63qxojbr.cloudfront.net/clip.mp4");
        assertRejected("https://d1ndex63qxojbr.cloudfront.net.evil.example/clip.mp4");
        assertRejected("https://d1ndex63qxojbrx.cloudfront.net/clip.mp4");
    }

    @Test
    void rejectsLookalikeAndSubdomainHostsWithoutReturningUrlDetails() {
        assertRejected("https://sub.production.assets.clips.twitchcdn.net/clip.mp4?sig=private-value");
        assertRejected("https://production.assets.clips.twitchcdn.net.evil.example/clip.mp4?sig=private-value");
        assertRejected("https://clips-media-assets2.twitch.tv.evil.example/clip.mp4");
    }

    @Test
    void rejectsNonHttpsPortsAndUserInfoWithoutReturningSensitiveUriParts() {
        assertRejected("http://production.assets.clips.twitchcdn.net/clip.mp4");
        assertRejected("https://production.assets.clips.twitchcdn.net:444/clip.mp4");
        var failure = assertThrows(TwitchClipMediaDownloader.DownloadFailure.class,
                () -> TwitchClipMediaDownloader.validateUri(URI.create(
                        "https://user:private-password@production.assets.clips.twitchcdn.net/clip.mp4?sig=private-value")));
        assertEquals("clip_media_url_rejected", failure.reason());
        assertEquals("clip_media_url_rejected", failure.getMessage());
        assertFalse(failure.getMessage().contains("private-password"));
        assertFalse(failure.getMessage().contains("private-value"));
    }

    @Test
    void malformedUriLogsNoInputAndReturnsStaticSafeReason() {
        var failure = assertThrows(TwitchClipMediaDownloader.DownloadFailure.class,
                () -> TwitchClipMediaDownloader.validateUri(null));
        assertEquals("clip_media_url_rejected", failure.reason());
        assertEquals("clip_media_url_rejected", failure.getMessage());
    }

    private static void assertRejected(String value) {
        var failure = assertThrows(TwitchClipMediaDownloader.DownloadFailure.class,
                () -> TwitchClipMediaDownloader.validateUri(URI.create(value)));
        assertEquals("clip_media_url_rejected", failure.reason());
        assertEquals("clip_media_url_rejected", failure.getMessage());
        assertFalse(failure.getMessage().contains(value));
    }

    private static void assertUnsupported(String contentType, byte[] bytes) {
        var failure = assertThrows(TwitchClipMediaDownloader.DownloadFailure.class,
                () -> TwitchClipMediaDownloader.normalizeContentType(contentType, bytes));
        assertEquals("unsupported_clip_media_type", failure.reason());
        assertEquals(415, failure.status());
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
        return bytes;
    }
}
