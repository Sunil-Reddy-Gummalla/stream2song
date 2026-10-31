package com.stream2song.backend.service;

import java.net.URI;
import java.time.Instant;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.stream2song.backend.config.TwitchOAuthProperties;
import com.stream2song.backend.dto.TwitchAppAccessTokenResponse;

/** Holds and renews the server-side app token shared by Twitch API clients. */
@Service
public class TwitchAppAccessTokenService {
    private static final URI TOKEN_URI = URI.create("https://id.twitch.tv/oauth2/token");
    private final TwitchOAuthProperties oauthProperties;
    private final RestTemplate restTemplate;
    private CachedToken cachedToken;

    public TwitchAppAccessTokenService(TwitchOAuthProperties oauthProperties, RestTemplate twitchRestTemplate) {
        this.oauthProperties = oauthProperties;
        this.restTemplate = twitchRestTemplate;
    }

    public synchronized String accessToken() {
        if (!oauthProperties.isConfigured()) {
            throw new IllegalStateException("Twitch OAuth client configuration is incomplete");
        }
        if (cachedToken != null && cachedToken.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return cachedToken.value();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", oauthProperties.clientId());
        form.add("client_secret", oauthProperties.clientSecret());
        form.add("grant_type", "client_credentials");

        TwitchAppAccessTokenResponse response = restTemplate.postForObject(
                TOKEN_URI, new HttpEntity<>(form, headers), TwitchAppAccessTokenResponse.class);
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()
                || response.expiresIn() <= 0) {
            throw new IllegalStateException("Twitch App Access Token unavailable");
        }
        cachedToken = new CachedToken(response.accessToken(), Instant.now().plusSeconds(response.expiresIn()));
        return response.accessToken();
    }

    private record CachedToken(String value, Instant expiresAt) {
        @Override
        public String toString() {
            return "CachedToken[redacted]";
        }
    }
}
