package com.stream2song.backend.service;

public interface OpenAiRealtimeWebSocketTransport {
    byte[] synthesizePcm(String apiKey, String model, String voice, String instructions, String lyrics,
            int maxAudioBytes);
}
