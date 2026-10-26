package com.stream2song.backend.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "twitch.eventsub")
public record TwitchEventSubProperties(String callbackUrl, String signingSecret) {

    public boolean isConfigured() {
        return isValidCallbackUrl() && isValidSigningSecret();
    }

    /** Safe diagnostic containing configuration names/rules only; never includes the configured values. */
    public String configurationProblem() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (callbackUrl == null || callbackUrl.isBlank()) {
            problems.add("TWITCH_EVENTSUB_CALLBACK_URL is missing");
        } else if (!isValidCallbackUrl()) {
            problems.add("TWITCH_EVENTSUB_CALLBACK_URL must be a public HTTPS URL on port 443");
        }
        if (!isValidSigningSecret()) {
            problems.add("TWITCH_EVENTSUB_SIGNING_SECRET must be 10–100 printable ASCII characters");
        }
        return String.join("; ", problems);
    }

    public boolean isValidCallbackUrl() {
        if (callbackUrl == null || callbackUrl.isBlank()) return false;
        try {
            URI uri = URI.create(callbackUrl);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && !"localhost".equalsIgnoreCase(uri.getHost())
                    && !uri.getHost().endsWith(".localhost");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public boolean isValidSigningSecret() {
        if (signingSecret == null || signingSecret.length() < 10 || signingSecret.length() > 100) return false;
        return signingSecret.chars().allMatch(character -> character >= 0x20 && character <= 0x7e);
    }

    @Override
    public String toString() {
        return "TwitchEventSubProperties[callbackUrl=" + callbackUrl + ", signingSecret=redacted]";
    }
}
