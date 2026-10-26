package com.stream2song.backend.service;

@FunctionalInterface
public interface TwitchBroadcasterProvider {
    String connectedBroadcasterId();
}
