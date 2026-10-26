package com.stream2song.backend.dto;

public record TwitchChatSummaryResponse(
        boolean collecting,
        String collectionStatus,
        String streamId,
        long totalMessages) { }
