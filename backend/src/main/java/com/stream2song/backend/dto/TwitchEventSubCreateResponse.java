package com.stream2song.backend.dto;

import java.util.List;

public record TwitchEventSubCreateResponse(List<Subscription> data) {
    public record Subscription(String id, String status, String type) { }
}
