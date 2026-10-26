package com.stream2song.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "twitch")
public record TwitchOAuthProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String frontendBaseUrl) {

    public boolean isConfigured() {
        return hasText(clientId) && hasText(clientSecret) && hasText(redirectUri);
    }

    @Override
    public String toString() {
        return "TwitchOAuthProperties[clientId=redacted, clientSecret=redacted, redirectUri="
                + redirectUri + ", frontendBaseUrl=" + frontendBaseUrl + "]";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
