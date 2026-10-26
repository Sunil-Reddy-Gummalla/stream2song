package com.stream2song.backend.dto;

import java.time.Instant;
import java.util.Map;

public record TwitchStreamSessionResponse(
        boolean connected,
        String broadcasterId,
        String login,
        boolean online,
        String twitchStreamId,
        String eventType,
        Instant eventTimestamp,
        Map<String, String> subscriptionStatuses) {

    public TwitchStreamSessionResponse {
        subscriptionStatuses = Map.copyOf(subscriptionStatuses);
    }

    public static TwitchStreamSessionResponse disconnected() {
        return new TwitchStreamSessionResponse(false, null, null, false, null, null, null, Map.of());
    }
}
