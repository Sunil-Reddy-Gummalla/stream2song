package com.stream2song.backend.model;

import java.time.Instant;

public record StreamSessionState(
        String broadcasterId,
        String twitchStreamId,
        String eventType,
        Instant eventTimestamp,
        boolean online) {
}
