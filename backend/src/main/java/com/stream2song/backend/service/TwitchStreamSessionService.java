package com.stream2song.backend.service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import com.stream2song.backend.model.StreamSessionState;

@Service
public class TwitchStreamSessionService {
    private static final Logger log = LoggerFactory.getLogger(TwitchStreamSessionService.class);
    private final AtomicReference<StreamSessionState> current = new AtomicReference<>();

    public synchronized void recordOnline(String broadcasterId, String streamId, Instant startedAt) {
        StreamSessionState previous = current.get();
        if (isOlderThanCurrent(broadcasterId, startedAt, previous)) return;
        current.set(new StreamSessionState(broadcasterId, streamId, "stream.online", startedAt, true, startedAt));
        if (previous == null || !previous.online() || !streamId.equals(previous.twitchStreamId())) {
            log.info("Twitch stream online: broadcasterId={} streamId={} eventTimestamp={}",
                    broadcasterId, streamId, startedAt);
        }
    }

    public synchronized void recordOffline(String broadcasterId, Instant eventTimestamp) {
        StreamSessionState previous = current.get();
        if (isOlderThanCurrent(broadcasterId, eventTimestamp, previous)) return;
        String lastStreamId = previous != null && previous.broadcasterId().equals(broadcasterId)
                ? previous.twitchStreamId() : null;
        Instant startedAt = previous != null && previous.broadcasterId().equals(broadcasterId)
                ? previous.streamStartedAt() : null;
        current.set(new StreamSessionState(broadcasterId, lastStreamId, "stream.offline", eventTimestamp, false, startedAt));
        if (previous == null || previous.online()) {
            log.info("Twitch stream offline: broadcasterId={} streamId={} eventTimestamp={}",
                    broadcasterId, lastStreamId, eventTimestamp);
        }
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
