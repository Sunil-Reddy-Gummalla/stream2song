package com.stream2song.backend.service;

import java.util.List;

public interface MomentInterpretationProvider {
    default boolean isConfigured() { return true; }
    List<Interpretation> interpret(List<InputMoment> moments, String timingContext);

    record InputMoment(String momentId, Double startSeconds, Double endSeconds, List<Evidence> evidence, String kind) {
        public InputMoment(String momentId, Double startSeconds, Double endSeconds, List<Evidence> evidence) {
            this(momentId, startSeconds, endSeconds, evidence, "clip_moment");
        }
    }
    record Evidence(String referenceId, String kind, String excerpt, Double startSeconds, Double endSeconds) { }
    record Interpretation(String momentId, String summary, String emotionalTone, String humorExcitement,
            String narrativeSignificance, List<String> evidenceReferences, String kind) {
        public Interpretation(String momentId, String summary, String emotionalTone, String humorExcitement,
                String narrativeSignificance, List<String> evidenceReferences) {
            this(momentId, summary, emotionalTone, humorExcitement, narrativeSignificance, evidenceReferences, "clip_moment");
        }
    }
}
