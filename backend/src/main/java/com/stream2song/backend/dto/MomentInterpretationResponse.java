package com.stream2song.backend.dto;

import java.util.List;

public record MomentInterpretationResponse(String interpretationId, String analysisId, String clipId, boolean aiGenerated,
        boolean unverifiedInterpretation, List<InterpretedMoment> moments, List<InterpretedContext> sessionContexts,
        List<InterpretedClipContext> transcriptContexts) {
    public MomentInterpretationResponse {
        moments = moments == null ? List.of() : List.copyOf(moments);
        sessionContexts = sessionContexts == null ? List.of() : List.copyOf(sessionContexts);
        transcriptContexts = transcriptContexts == null ? List.of() : List.copyOf(transcriptContexts);
    }
    public MomentInterpretationResponse(String interpretationId, String analysisId, String clipId, boolean aiGenerated,
            boolean unverifiedInterpretation, List<InterpretedMoment> moments) {
        this(interpretationId, analysisId, clipId, aiGenerated, unverifiedInterpretation, moments, List.of(), List.of());
    }
    public MomentInterpretationResponse(String interpretationId, String analysisId, String clipId, boolean aiGenerated,
            boolean unverifiedInterpretation, List<InterpretedMoment> moments, List<InterpretedContext> sessionContexts) {
        this(interpretationId, analysisId, clipId, aiGenerated, unverifiedInterpretation, moments, sessionContexts, List.of());
    }
    public MomentInterpretationResponse(String analysisId, String clipId, boolean aiGenerated,
            boolean unverifiedInterpretation, List<InterpretedMoment> moments) {
        this("legacy", analysisId, clipId, aiGenerated, unverifiedInterpretation, moments, List.of(), List.of());
    }
    public record InterpretedMoment(String momentId, String summary, String emotionalTone, String humorExcitement,
            String narrativeSignificance, Double startSeconds, Double endSeconds, String association,
            List<EvidenceCitation> evidence) { }
    public record InterpretedContext(String contextId, String summary, String emotionalTone, String humorExcitement,
            String narrativeSignificance, String association, List<EvidenceCitation> evidence) {
        public InterpretedContext { evidence = evidence == null ? List.of() : List.copyOf(evidence); }
    }
    public record InterpretedClipContext(String contextId, String summary, String emotionalTone, String humorExcitement,
            String narrativeSignificance, Double startSeconds, Double endSeconds, String association,
            List<EvidenceCitation> evidence) {
        public InterpretedClipContext { evidence = evidence == null ? List.of() : List.copyOf(evidence); }
    }
    public record EvidenceCitation(String id, String kind, String excerpt, Double startSeconds, Double endSeconds) { }
}
