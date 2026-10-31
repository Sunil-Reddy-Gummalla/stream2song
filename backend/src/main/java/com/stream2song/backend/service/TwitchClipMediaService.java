package com.stream2song.backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.stream2song.backend.client.TwitchClipDownloadsApiClient;
import com.stream2song.backend.client.TwitchClipDownloadsApiResponse;
import com.stream2song.backend.dto.TwitchClipMediaResponse;
import com.stream2song.backend.service.TwitchBroadcasterProvider.ConnectionScope;

@Service
public class TwitchClipMediaService {
    private final TwitchBroadcasterProvider broadcasterProvider;
    private final TwitchClipDownloadsApiClient apiClient;

    public TwitchClipMediaService(TwitchBroadcasterProvider broadcasterProvider, TwitchClipDownloadsApiClient apiClient) {
        this.broadcasterProvider = broadcasterProvider;
        this.apiClient = apiClient;
    }

    public ConnectionScope connectionScope() { return broadcasterProvider.connectionScope(); }

    public TwitchClipMediaResponse downloadUrls(String clipId) {
        return downloadUrls(clipId, broadcasterProvider.connectionScope());
    }

    public TwitchClipMediaResponse downloadUrls(String clipId, ConnectionScope expectedScope) {
        if (clipId == null || !clipId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new ClipMediaFailure(HttpStatus.BAD_REQUEST, "invalid_clip_id");
        }
        if (expectedScope == null || expectedScope.broadcasterId() == null || expectedScope.broadcasterId().isBlank()) {
            throw new ClipMediaFailure(HttpStatus.CONFLICT, "twitch_account_not_connected");
        }
        String broadcasterId = expectedScope.broadcasterId();
        if (!broadcasterProvider.isCurrentConnection(expectedScope)) {
            throw new ClipMediaFailure(HttpStatus.CONFLICT, "twitch_account_changed");
        }
        try {
            TwitchClipDownloadsApiResponse response = apiClient.getClipDownload(expectedScope, clipId);
            if (!broadcasterProvider.isCurrentConnection(expectedScope)) {
                throw new ClipMediaFailure(HttpStatus.CONFLICT, "twitch_account_changed");
            }
            if (response == null || response.data() == null || response.data().isEmpty()) {
                throw new ClipMediaFailure(HttpStatus.NOT_FOUND, "twitch_clip_media_unavailable");
            }
            var clip = response.data().stream().filter(item -> clipId.equals(item.clipId())).findFirst().orElse(null);
            if (clip == null || (clip.landscapeDownloadUrl() == null && clip.portraitDownloadUrl() == null)) {
                throw new ClipMediaFailure(HttpStatus.NOT_FOUND, "twitch_clip_media_unavailable");
            }
            return new TwitchClipMediaResponse(clipId, clip.landscapeDownloadUrl(), clip.portraitDownloadUrl());
        } catch (TwitchClipDownloadsApiClient.ClipDownloadApiFailure failure) {
            if (failure.status() == 401) throw new ClipMediaFailure(HttpStatus.UNAUTHORIZED, "twitch_clip_authorization_required");
            if (failure.status() == 403) throw new ClipMediaFailure(HttpStatus.FORBIDDEN, "twitch_clip_permission_denied");
            if (failure.status() == 404) throw new ClipMediaFailure(HttpStatus.NOT_FOUND, "twitch_clip_media_unavailable");
            if (failure.status() == 429) throw new ClipMediaFailure(HttpStatus.TOO_MANY_REQUESTS, "twitch_rate_limited");
            if (failure.status() == 409) throw new ClipMediaFailure(HttpStatus.CONFLICT, "twitch_account_changed");
            throw new ClipMediaFailure(HttpStatus.BAD_GATEWAY, "twitch_api_request_failed");
        }
    }

    public static class ClipMediaFailure extends RuntimeException {
        private final HttpStatus status;
        private final String reason;
        public ClipMediaFailure(HttpStatus status, String reason) { this.status = status; this.reason = reason; }
        public HttpStatus status() { return status; }
        public String reason() { return reason; }
    }
}
