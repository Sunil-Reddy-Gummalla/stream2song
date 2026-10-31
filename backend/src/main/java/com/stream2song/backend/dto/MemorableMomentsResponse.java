package com.stream2song.backend.dto;

import java.time.Instant;
import java.util.List;

public record MemorableMomentsResponse(
        String analysisId,
        String clipId,
        SourceClip source,
        String association,
        String chatCorrelation,
        String chatTimingBasis,
        String chatCorrelationExplanation,
        boolean heuristicOnly,
        String scoreScale,
        String message,
        List<Moment> moments,
        SessionChatContext sessionChatContext,
        TranscriptContext transcriptContext) {

    public MemorableMomentsResponse(String analysisId, String clipId, SourceClip source, String association,
            String chatCorrelation, String chatTimingBasis, String chatCorrelationExplanation,
            boolean heuristicOnly, String scoreScale, String message, List<Moment> moments) {
        this(analysisId, clipId, source, association, chatCorrelation, chatTimingBasis,
                chatCorrelationExplanation, heuristicOnly, scoreScale, message, moments, SessionChatContext.empty(), TranscriptContext.empty());
    }

    public MemorableMomentsResponse(String analysisId, String clipId, SourceClip source, String association,
            String chatCorrelation, String chatTimingBasis, String chatCorrelationExplanation,
            boolean heuristicOnly, String scoreScale, String message, List<Moment> moments,
            SessionChatContext sessionChatContext) {
        this(analysisId, clipId, source, association, chatCorrelation, chatTimingBasis, chatCorrelationExplanation,
                heuristicOnly, scoreScale, message, moments, sessionChatContext, TranscriptContext.empty());
    }

    public MemorableMomentsResponse(String clipId, SourceClip source, String association, String chatCorrelation,
            String chatTimingBasis, String chatCorrelationExplanation, boolean heuristicOnly, String scoreScale,
            String message, List<Moment> moments) {
        this(null, clipId, source, association, chatCorrelation, chatTimingBasis, chatCorrelationExplanation,
                heuristicOnly, scoreScale, message, moments, SessionChatContext.empty(), TranscriptContext.empty());
    }

    public record SourceClip(String title, String creatorName, double durationSeconds, String vodId,
            Integer vodOffsetSeconds) { }

    public record Moment(String id, String description, Double startSeconds, Double endSeconds, int score,
            List<TranscriptEvidence> transcriptEvidence, List<ChatEvidence> chatEvidence, String sourceClipId,
            String association, String selectionExplanation, List<String> contributors) { }

    public record TranscriptEvidence(String text, Double startSeconds, Double endSeconds) { }

    public record ChatEvidence(Instant windowStartUtc, Instant windowEndUtc, Double startSeconds,
            Double endSeconds, double score, String summary, String explanation,
            int messageCount, List<String> messages) { }

    public record SessionChatContext(long availableCount, long analyzedCount, long suppliedCount,
            long truncatedCount, List<SessionChatMessage> messages, String association) {
        public SessionChatContext(long availableCount, long analyzedCount, long suppliedCount,
                long truncatedCount, List<SessionChatMessage> messages) {
            this(availableCount, analyzedCount, suppliedCount, truncatedCount, messages, "unknown");
        }
        public SessionChatContext { messages = messages == null ? List.of() : List.copyOf(messages); }
        public static SessionChatContext empty() { return new SessionChatContext(0, 0, 0, 0, List.of(), "unknown"); }
    }

    public record SessionChatMessage(String id, String text) { }

    public record TranscriptContext(long availableCount, long analyzedCount, long suppliedCount,
            long truncatedCount, long suppliedCharacters, long truncatedCharacters, String association,
            String sourceType, List<TranscriptUtterance> utterances) {
        public TranscriptContext { utterances = utterances == null ? List.of() : List.copyOf(utterances); }
        public static TranscriptContext empty() { return new TranscriptContext(0, 0, 0, 0, 0, 0, "unknown", "none", List.of()); }
    }

    public record TranscriptUtterance(String id, String text, Double startSeconds, Double endSeconds) { }
}
