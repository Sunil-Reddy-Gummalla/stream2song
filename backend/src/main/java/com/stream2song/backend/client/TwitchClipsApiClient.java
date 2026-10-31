package com.stream2song.backend.client;

import java.net.URI;
import java.time.Instant;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.stream2song.backend.config.TwitchOAuthProperties;
import com.stream2song.backend.service.TwitchAppAccessTokenService;

@Component
public class TwitchClipsApiClient {
    private static final URI CLIPS_URI = URI.create("https://api.twitch.tv/helix/clips");

    private final RestTemplate restTemplate;
    private final TwitchOAuthProperties oauthProperties;
    private final TwitchAppAccessTokenService appAccessTokenService;

    public TwitchClipsApiClient(RestTemplate twitchRestTemplate, TwitchOAuthProperties oauthProperties,
            TwitchAppAccessTokenService appAccessTokenService) {
        this.restTemplate = twitchRestTemplate;
        this.oauthProperties = oauthProperties;
        this.appAccessTokenService = appAccessTokenService;
    }

    public TwitchClipsApiResponse getBroadcasterClips(String broadcasterId, int limit, String cursor,
            Instant startedAt, Instant endedAt) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(CLIPS_URI)
                .queryParam("broadcaster_id", broadcasterId)
                .queryParam("first", limit)
                .queryParam("started_at", startedAt.toString())
                .queryParam("ended_at", endedAt.toString());
        if (cursor != null) builder.queryParam("after", cursor);
        URI uri = builder.build().encode().toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(appAccessTokenService.accessToken());
        headers.set("Client-Id", oauthProperties.clientId());
        return restTemplate.exchange(uri, org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), TwitchClipsApiResponse.class).getBody();
    }

    public TwitchClipsApiResponse getClipById(String clipId) {
        URI uri = UriComponentsBuilder.fromUri(CLIPS_URI).queryParam("id", clipId).build().encode().toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(appAccessTokenService.accessToken());
        headers.set("Client-Id", oauthProperties.clientId());
        return restTemplate.exchange(uri, org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), TwitchClipsApiResponse.class).getBody();
    }
}
