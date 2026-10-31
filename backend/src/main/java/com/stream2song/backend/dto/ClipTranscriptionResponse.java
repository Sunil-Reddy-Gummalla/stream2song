package com.stream2song.backend.dto;

import java.util.List;

public record ClipTranscriptionResponse(String clipId, String text, List<TimedTranscriptSegment> segments) {
    public record TimedTranscriptSegment(Double startSeconds, Double endSeconds, String text) { }
}
