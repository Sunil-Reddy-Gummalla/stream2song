package com.stream2song.backend.service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.stream2song.backend.model.StreamSessionState;

@Service
public class TwitchStreamSessionService {
    private final AtomicReference<StreamSessionState> current = new AtomicReference<>();

    public void recordOnline(String broadcasterId, String streamId, Instant startedAt) {
        current.updateAndGet(previous -> {
            if (isOlderThanCurrent(broadcasterId, startedAt, previous)) return previous;
            return new StreamSessionState(broadcasterId, streamId, "stream.online", startedAt, true);
        });
    }

    public void recordOffline(String broadcasterId, Instant eventTimestamp) {
        current.updateAndGet(previous -> {
            if (isOlderThanCurrent(broadcasterId, eventTimestamp, previous)) return previous;
            String lastStreamId = previous != null && previous.broadcasterId().equals(broadcasterId)
                    ? previous.twitchStreamId() : null;
            return new StreamSessionState(broadcasterId, lastStreamId, "stream.offline", eventTimestamp, false);
        });
    }

    public StreamSessionState currentFor(String broadcasterId) {
        StreamSessionState state = current.get();
        return state != null && state.broadcasterId().equals(broadcasterId) ? state : null;
    }

    private boolean isOlderThanCurrent(String broadcasterId, Instant eventTimestamp, StreamSessionState previous) {
        return previous != null && previous.broadcasterId().equals(broadcasterId)
                && eventTimestamp.isBefore(previous.eventTimestamp());
    }
}
