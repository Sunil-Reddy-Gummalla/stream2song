package com.stream2song.backend.dto;

import java.util.List;

public record ClipLyricsResponse(String lyricsId, String analysisId, String interpretationId, String clipId,
        String genre, String mood, String energy, boolean aiGenerated, boolean unverified,
        String association, String chatTimingBasis, String chatCorrelationExplanation,
        String title, List<Section> sections) {
    public record Section(String kind, String label, List<String> lines, List<SourceMoment> sources) { }
    public record SourceMoment(String momentId, Double startSeconds, Double endSeconds, String association,
            List<MomentInterpretationResponse.EvidenceCitation> evidence) { }
}
