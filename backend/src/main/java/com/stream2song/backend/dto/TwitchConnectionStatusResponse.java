package com.stream2song.backend.dto;

public record TwitchConnectionStatusResponse(boolean connected, String broadcasterId, String login) {

    public static TwitchConnectionStatusResponse disconnected() {
        return new TwitchConnectionStatusResponse(false, null, null);
    }

    public static TwitchConnectionStatusResponse connected(String broadcasterId, String login) {
        return new TwitchConnectionStatusResponse(true, broadcasterId, login);
    }
}
