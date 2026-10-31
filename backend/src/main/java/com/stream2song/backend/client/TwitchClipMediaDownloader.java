package com.stream2song.backend.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLConnection;
import javax.net.ssl.HttpsURLConnection;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stream2song.backend.service.ClipMediaDownloader;

@Component
public class TwitchClipMediaDownloader implements ClipMediaDownloader {
    private static final Logger log = LoggerFactory.getLogger(TwitchClipMediaDownloader.class);
    public static final int MAX_MEDIA_BYTES = 50 * 1024 * 1024;
    private static final Set<String> ALLOWED_HOSTS = Set.of("clips-media-assets.twitch.tv",
            "clips-media-assets2.twitch.tv", "clips.twitchcdn.net", "production.assets.clips.twitchcdn.net",
            "d1ndex63qxojbr.cloudfront.net");
    private static final Set<String> ALLOWED_TYPES = Set.of("audio/mpeg", "audio/mp3", "audio/mp4", "audio/aac",
            "audio/wav", "audio/x-wav", "audio/ogg", "audio/webm", "video/mp4", "video/webm");
    @Override
    public DownloadedMedia download(URI uri) {
        validateUri(uri);
        HttpsURLConnection connection = null;
        try {
            URLConnection rawConnection = uri.toURL().openConnection();
            if (!(rawConnection instanceof HttpsURLConnection https)) throw new DownloadFailure("clip_media_url_rejected", 502);
            connection = https;
            connection.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
            connection.setReadTimeout((int) Duration.ofSeconds(20).toMillis());
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            try (InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                if (status == 401 || status == 403 || status == 404 || status == 410) {
                    throw new DownloadFailure("clip_media_expired", 404);
                }
                if (status != 200 || body == null) throw new DownloadFailure("clip_media_fetch_failed", 502);
                String contentType = connection.getContentType() == null ? ""
                        : connection.getContentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                long length = connection.getContentLengthLong();
                if (length > MAX_MEDIA_BYTES) throw new DownloadFailure("clip_media_too_large", 413);
                byte[] bytes = readBounded(body, MAX_MEDIA_BYTES);
                return new DownloadedMedia(bytes, normalizeContentType(contentType, bytes));
            }
        } catch (DownloadFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof DownloadFailure failure) throw failure;
            if (exception instanceof SocketTimeoutException) throw new DownloadFailure("clip_media_timeout", 504);
            throw new DownloadFailure("clip_media_fetch_failed", 502);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static void validateUri(URI uri) {
        if (uri == null || uri.getHost() == null) reject("malformed_uri", null);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())) reject("scheme_not_https", host);
        if (uri.getUserInfo() != null) reject("userinfo_not_allowed", host);
        if (uri.getPort() != -1 && uri.getPort() != 443) reject("port_not_allowed", host);
        if (!ALLOWED_HOSTS.contains(host)) reject("host_not_allowlisted", host);
    }

    private static void reject(String category, String parsedHost) {
        if (parsedHost == null) {
            log.warn("Rejected Twitch clip media URL: category={}", category);
        } else {
            String safeHost = parsedHost.matches("[A-Za-z0-9.-]{1,253}") ? parsedHost : "invalid-host";
            log.warn("Rejected Twitch clip media URL: category={} host={}", category, safeHost);
        }
        throw new DownloadFailure("clip_media_url_rejected", 502);
    }

    static boolean isSupportedContentType(String contentType) { return ALLOWED_TYPES.contains(contentType); }

    static String normalizeContentType(String contentType, byte[] bytes) {
        if (isSupportedContentType(contentType)) return contentType;
        return detectMediaType(bytes);
    }

    static String detectMediaType(byte[] bytes) {
        if (bytes != null && bytes.length >= 12 && matches(bytes, 4, "ftyp")) return "video/mp4";
        if (startsWith(bytes, 0x1a, 0x45, 0xdf, 0xa3)) return "video/webm";
        if (startsWith(bytes, 'R', 'I', 'F', 'F') && matches(bytes, 8, "WAVE")) return "audio/wav";
        if (startsWith(bytes, 'O', 'g', 'g', 'S')) return "audio/ogg";
        if (isMp3(bytes)) return "audio/mpeg";
        if (isAdtsAac(bytes)) return "audio/aac";
        throw new DownloadFailure("unsupported_clip_media_type", 415);
    }

    private static boolean isMp3(byte[] bytes) {
        if (!startsWith(bytes, 'I', 'D', '3') || bytes.length < 10) return false;
        if ((bytes[3] & 0xff) == 0xff || (bytes[4] & 0xff) == 0xff) return false;
        int tagSize = 0;
        for (int i = 6; i < 10; i++) {
            if ((bytes[i] & 0x80) != 0) return false;
            tagSize = (tagSize << 7) | (bytes[i] & 0x7f);
        }
        return tagSize <= bytes.length - 10;
    }

    private static boolean isAdtsAac(byte[] bytes) {
        if (bytes == null || bytes.length < 7 || (bytes[0] & 0xff) != 0xff || ((bytes[1] & 0xf6) != 0xf0)) return false;
        int frameLength = ((bytes[3] & 0x03) << 11) | ((bytes[4] & 0xff) << 3) | ((bytes[5] & 0xe0) >>> 5);
        return frameLength >= 7 && frameLength <= bytes.length;
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes == null || bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 0xff) != prefix[i]) return false;
        return true;
    }

    private static boolean matches(byte[] bytes, int offset, String ascii) {
        if (bytes == null || bytes.length < offset + ascii.length()) return false;
        for (int i = 0; i < ascii.length(); i++) if (bytes[offset + i] != (byte) ascii.charAt(i)) return false;
        return true;
    }

    static byte[] readBounded(InputStream body, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(8192, maxBytes));
        byte[] chunk = new byte[8192];
        int read;
        while ((read = body.read(chunk)) != -1) {
            if (output.size() + read > maxBytes) throw new DownloadFailure("clip_media_too_large", 413);
            output.write(chunk, 0, read);
        }
        return output.toByteArray();
    }

    public static class DownloadFailure extends RuntimeException {
        private final String reason;
        private final int status;
        public DownloadFailure(String reason, int status) { super(reason); this.reason = reason; this.status = status; }
        public String reason() { return reason; }
        public int status() { return status; }
    }
}
