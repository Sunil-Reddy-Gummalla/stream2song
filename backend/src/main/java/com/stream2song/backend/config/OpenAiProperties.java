package com.stream2song.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(String apiKey, String model) {
    public OpenAiProperties {
        model = model == null || model.isBlank() ? "gpt-6-luna" : model;
    }
    public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }
    @Override public String toString() { return "OpenAiProperties[apiKey=redacted, model=" + model + "]"; }
}
