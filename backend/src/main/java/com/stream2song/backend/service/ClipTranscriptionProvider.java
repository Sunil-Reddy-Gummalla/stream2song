package com.stream2song.backend.service;

import java.util.List;

public interface ClipTranscriptionProvider {
    default boolean isConfigured() { return true; }
    default String cacheFingerprint() { return getClass().getName(); }
    Transcript transcribe(byte[] media, String contentType);

    record Transcript(String text, List<Segment> segments) { }
    record Segment(double startSeconds, double endSeconds, String text) { }
}
