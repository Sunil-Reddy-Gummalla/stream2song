package com.stream2song.backend.service;

import java.util.List;

public interface LyricsGenerationProvider {
    boolean isConfigured();
    Draft generate(String evidenceJson, String genre, String mood, String energy);
    record Draft(String title, List<SectionDraft> sections) { }
    record SectionDraft(String kind, String label, List<String> lines, List<String> sourceMomentIds,
            List<String> sourceEvidenceIds) { }
}
