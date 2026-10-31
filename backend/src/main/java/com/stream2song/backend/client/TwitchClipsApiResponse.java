package com.stream2song.backend.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Twitch Get Clips response shape; this type stays within the Twitch API client layer. */
public record TwitchClipsApiResponse(List<Clip> data, Pagination pagination) {
    public record Clip(
            String id,
            @JsonProperty("broadcaster_id") String broadcasterId,
            String title,
            @JsonProperty("creator_name") String creatorName,
            @JsonProperty("created_at") String createdAt,
            double duration,
            String url,
            @JsonProperty("thumbnail_url") String thumbnailUrl,
            @JsonProperty("video_id") String videoId,
            @JsonProperty("vod_offset") Integer vodOffset) { }

    public record Pagination(String cursor) { }
}
