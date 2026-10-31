package com.stream2song.backend.client;

import java.net.URI;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.stream2song.backend.config.TwitchOAuthProperties;
import com.stream2song.backend.service.TwitchOAuthService;
import com.stream2song.backend.service.TwitchBroadcasterProvider.ConnectionScope;

@Component
public class TwitchClipDownloadsApiClient {
    private static final URI DOWNLOADS_URI = URI.create("https://api.twitch.tv/helix/clips/downloads");
    private final RestTemplate restTemplate;
    private final TwitchOAuthProperties oauthProperties;
    private final TwitchOAuthService oauthService;

    public TwitchClipDownloadsApiClient(RestTemplate twitchRestTemplate, TwitchOAuthProperties oauthProperties,
            TwitchOAuthService oauthService) {
        this.restTemplate = twitchRestTemplate;
        this.oauthProperties = oauthProperties;
        this.oauthService = oauthService;
    }

    public TwitchClipDownloadsApiResponse getClipDownload(String broadcasterId, String clipId) {
        ConnectionScope scope = oauthService.connectionScope();
        if (scope == null) throw new ClipDownloadApiFailure(401);
        if (!broadcasterId.equals(scope.broadcasterId())) throw new ClipDownloadApiFailure(409);
        return getClipDownload(scope, clipId);
    }

    public TwitchClipDownloadsApiResponse getClipDownload(ConnectionScope scope, String clipId) {
        TwitchOAuthService.ClipAuthorizationContext authorization = oauthService.clipAuthorizationContext(scope);
        if (authorization == null) throw new ClipDownloadApiFailure(409);
        if (!authorization.clipDownloadScope()) throw new ClipDownloadApiFailure(403);
        URI uri = UriComponentsBuilder.fromUri(DOWNLOADS_URI)
                .queryParam("editor_id", scope.broadcasterId()).queryParam("broadcaster_id", scope.broadcasterId())
                .queryParam("clip_id", clipId).build().encode().toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authorization.accessToken());
        headers.set("Client-Id", oauthProperties.clientId());
        try {
            TwitchClipDownloadsApiResponse response = restTemplate.exchange(uri, org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers), TwitchClipDownloadsApiResponse.class).getBody();
            if (!oauthService.isCurrentConnection(scope)) throw new ClipDownloadApiFailure(409);
            return response;
        } catch (ClipDownloadApiFailure failure) {
            throw failure;
        } catch (org.springframework.web.client.HttpStatusCodeException exception) {
            throw new ClipDownloadApiFailure(exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw new ClipDownloadApiFailure(502);
        }
    }

    public static class ClipDownloadApiFailure extends RuntimeException {
        private final int status;
        public ClipDownloadApiFailure(int status) { this.status = status; }
        public int status() { return status; }
    }
}
