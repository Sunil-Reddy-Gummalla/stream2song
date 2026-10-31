package com.stream2song.backend.dto;

import java.time.Instant;
import java.util.List;

public record TwitchChatHighlightsResponse(
        String streamId,
        int windowSeconds,
        int messagesAnalyzed,
        boolean heuristicOnly,
        String message,
        List<Highlight> highlights) {

    public record Highlight(
            Instant startTime,
            Instant endTime,
            double score,
            int messageCount,
            String summary,
            String explanation,
            List<String> representativeMessages) { }
}
