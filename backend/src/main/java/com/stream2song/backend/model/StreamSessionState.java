package com.stream2song.backend.model;

import java.time.Instant;

public record StreamSessionState(
        String broadcasterId,
        String twitchStreamId,
        String eventType,
        Instant eventTimestamp,
        boolean online,
        Instant streamStartedAt) {
    public StreamSessionState(String broadcasterId, String twitchStreamId, String eventType,
            Instant eventTimestamp, boolean online) {
        this(broadcasterId, twitchStreamId, eventType, eventTimestamp, online,
                online ? eventTimestamp : null);
    }
}
