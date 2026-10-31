package com.stream2song.backend.service;

public interface SpokenLyricsProvider {
    boolean isConfigured();
    String fingerprint();
    byte[] synthesize(String lyrics);
}
