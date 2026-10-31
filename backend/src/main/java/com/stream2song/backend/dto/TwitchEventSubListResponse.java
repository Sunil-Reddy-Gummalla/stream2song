package com.stream2song.backend.dto;

import java.util.List;
import java.util.Map;

public record TwitchEventSubListResponse(List<Subscription> data, Pagination pagination) {
    public record Subscription(
            String id,
            String status,
            String type,
            String version,
            Map<String, String> condition,
            Transport transport) { }

    public record Transport(String method, String callback) { }

    public record Pagination(String cursor) { }
}
