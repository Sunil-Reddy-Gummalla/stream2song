package com.stream2song.backend.dto;

import java.time.Instant;
import java.util.UUID;

import com.stream2song.backend.model.SongJob;

public record SongJobResponse(
        UUID id,
        String channelName,
        String status,
        Instant createdAt
) {
    public static SongJobResponse from(SongJob songJob) {
        return new SongJobResponse(
                songJob.id(),
                songJob.channelName(),
                songJob.status().name(),
                songJob.createdAt()
        );
    }
}
