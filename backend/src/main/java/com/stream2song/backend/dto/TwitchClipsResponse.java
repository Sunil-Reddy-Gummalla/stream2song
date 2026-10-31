package com.stream2song.backend.dto;

import java.time.Instant;
import java.util.List;

public record TwitchClipsResponse(
        String broadcasterId,
        String association,
        String targetSessionStreamId,
        Instant targetSessionEventTimestamp,
        Instant startedAt,
        Instant endedAt,
        String nextCursor,
        String associationNote,
        List<TwitchClipResponse> clips) { }
