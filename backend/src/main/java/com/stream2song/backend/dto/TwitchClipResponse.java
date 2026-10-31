package com.stream2song.backend.dto;

import java.time.Instant;

public record TwitchClipResponse(
        String id,
        String title,
        String creatorName,
        Instant createdAt,
        double duration,
        String url,
        String thumbnailUrl,
        String vodId,
        Integer vodOffset,
        String sessionAssociation) { }
