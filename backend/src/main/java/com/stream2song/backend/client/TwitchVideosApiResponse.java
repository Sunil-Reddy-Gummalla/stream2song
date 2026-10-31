package com.stream2song.backend.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Twitch Get Videos response fields needed for VOD ownership, type, and timeline validation. */
public record TwitchVideosApiResponse(List<Video> data) {
    public record Video(String id, @JsonProperty("stream_id") String streamId,
            @JsonProperty("user_id") String userId, String type,
            @JsonProperty("created_at") String createdAt, String duration) { }
}
