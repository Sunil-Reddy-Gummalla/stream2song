package com.stream2song.backend.service;

import org.springframework.stereotype.Service;
import java.time.Instant;

import com.stream2song.backend.model.StreamSessionState;

@Service
public class TwitchChatCollectionService {
    private final TwitchEventSubSubscriptionService subscriptionService;
    private final TwitchStreamSessionService sessionService;
    private final TwitchBroadcasterProvider broadcasterProvider;

    public TwitchChatCollectionService(TwitchEventSubSubscriptionService subscriptionService,
            TwitchStreamSessionService sessionService, TwitchBroadcasterProvider broadcasterProvider) {
        this.subscriptionService = subscriptionService;
        this.sessionService = sessionService;
        this.broadcasterProvider = broadcasterProvider;
    }

    public void streamOnline(String broadcasterId, String streamId) {
        StreamSessionState state = sessionService.currentFor(broadcasterId);
        if (state != null && state.online() && streamId.equals(state.twitchStreamId())
                && broadcasterId.equals(broadcasterProvider.connectedBroadcasterId())) {
            // The broadcaster is also the chat user for this prototype.
            subscriptionService.subscribeToChatMessages(broadcasterId, broadcasterId);
        }
    }

    public void streamOffline(String broadcasterId) {
        if (broadcasterId.equals(broadcasterProvider.connectedBroadcasterId()))
            subscriptionService.stopChatMessages(broadcasterId);
    }

    public void synchronizeCurrentStream(String broadcasterId) {
        StreamSessionState state = sessionService.currentFor(broadcasterId);
        com.stream2song.backend.dto.TwitchStreamsResponse.Stream active = subscriptionService.activeStream(broadcasterId);
        if (active != null && active.id() != null && active.startedAt() != null) {
            try {
                sessionService.recordOnline(broadcasterId, active.id(), Instant.parse(active.startedAt()));
                streamOnline(broadcasterId, active.id());
            } catch (java.time.format.DateTimeParseException exception) {
                subscriptionService.markChatStatus(broadcasterId, "failed");
            }
        } else if (active != null) {
            subscriptionService.markChatStatus(broadcasterId, "failed");
        } else if ("awaiting_stream".equals(subscriptionService.chatStatus(broadcasterId))) {
            if (state != null && state.online()) {
                sessionService.recordOffline(broadcasterId, Instant.now());
                subscriptionService.stopChatMessages(broadcasterId);
            }
        }
    }
}
