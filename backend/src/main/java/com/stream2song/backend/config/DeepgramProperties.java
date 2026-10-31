package com.stream2song.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "deepgram")
public record DeepgramProperties(String apiKey) {
    public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }
    @Override public String toString() { return "DeepgramProperties[apiKey=redacted]"; }
}
