package com.stream2song.backend.client;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.stream2song.backend.config.TwitchOAuthProperties;
import com.stream2song.backend.service.TwitchAppAccessTokenService;

@Component
public class TwitchVideosApiClient {
    private static final URI VIDEOS_URI = URI.create("https://api.twitch.tv/helix/videos");

    private final RestTemplate restTemplate;
    private final TwitchOAuthProperties oauthProperties;
    private final TwitchAppAccessTokenService appAccessTokenService;

    public TwitchVideosApiClient(RestTemplate restTemplate, TwitchOAuthProperties oauthProperties,
            TwitchAppAccessTokenService appAccessTokenService) {
        this.restTemplate = restTemplate;
        this.oauthProperties = oauthProperties;
        this.appAccessTokenService = appAccessTokenService;
    }

    public TwitchVideosApiResponse getVideosByIds(List<String> videoIds) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(VIDEOS_URI);
        videoIds.stream().filter(id -> id != null && !id.isBlank()).distinct().limit(100)
                .forEach(id -> builder.queryParam("id", id));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(appAccessTokenService.accessToken());
        headers.set("Client-Id", oauthProperties.clientId());
        return restTemplate.exchange(builder.build().encode().toUri(), org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), TwitchVideosApiResponse.class).getBody();
    }
}
