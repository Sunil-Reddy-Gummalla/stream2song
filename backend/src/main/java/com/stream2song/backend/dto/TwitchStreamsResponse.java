package com.stream2song.backend.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record TwitchStreamsResponse(List<Stream> data) {
    public record Stream(String id, @JsonProperty("started_at") String startedAt) { }
}
