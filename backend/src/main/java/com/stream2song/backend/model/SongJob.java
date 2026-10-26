package com.stream2song.backend.model;

import java.time.Instant;
import java.util.UUID;

public record SongJob(
        UUID id,
        String channelName,
        Status status,
        Instant createdAt
) {
    public enum Status {
        PENDING
    }
}
