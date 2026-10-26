package com.stream2song.backend.model;

import java.time.Instant;

public record TwitchChatMessage(
        String messageId,
        String broadcasterId,
        String streamId,
        Instant timestamp,
        String userId,
        String username,
        String text,
        String messageType,
        String color) { }
