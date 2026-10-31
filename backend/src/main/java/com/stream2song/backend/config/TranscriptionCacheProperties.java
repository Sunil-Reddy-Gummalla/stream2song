package com.stream2song.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clip.transcription.cache")
public record TranscriptionCacheProperties(long ttlSeconds, int maxEntries) {
    public TranscriptionCacheProperties {
        ttlSeconds = Math.max(60, Math.min(ttlSeconds <= 0 ? 1800 : ttlSeconds, 86_400));
        maxEntries = Math.max(1, Math.min(maxEntries <= 0 ? 100 : maxEntries, 100));
    }
    public TranscriptionCacheProperties() { this(1800, 100); }
}
