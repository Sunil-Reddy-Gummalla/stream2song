package com.stream2song.backend.service;

@FunctionalInterface
public interface TwitchBroadcasterProvider {
    String connectedBroadcasterId();

    default ConnectionScope connectionScope() {
        String broadcasterId = connectedBroadcasterId();
        return broadcasterId == null || broadcasterId.isBlank() ? null : new ConnectionScope(broadcasterId, 0L);
    }

    default boolean isCurrentConnection(ConnectionScope scope) {
        return scope != null && scope.equals(connectionScope());
    }

    record ConnectionScope(String broadcasterId, long generation) { }
}
