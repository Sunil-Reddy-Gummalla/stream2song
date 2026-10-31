package com.stream2song.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai.spoken")
public record OpenAiSpokenProperties(String model, String voice) {
    public OpenAiSpokenProperties {
        model = model == null || model.isBlank() ? "gpt-realtime-2.1-mini" : model;
        voice = voice == null || voice.isBlank() ? "marin" : voice;
    }
}
