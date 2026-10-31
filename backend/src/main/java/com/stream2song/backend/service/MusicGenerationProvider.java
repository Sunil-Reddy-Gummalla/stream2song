package com.stream2song.backend.service;

import java.net.URI;

public interface MusicGenerationProvider {
    boolean isConfigured();
    String fingerprint();
    Prediction submit(String lyrics,String prompt);
    Prediction poll(String providerPredictionId);
    byte[] download(URI outputUri);
    record Prediction(String id,String status,URI outputUri) { }
}
