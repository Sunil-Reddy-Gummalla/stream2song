package com.stream2song.backend.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.stream2song.backend.dto.TwitchChatSummaryResponse;
import com.stream2song.backend.model.StreamSessionState;
import com.stream2song.backend.model.TwitchChatMessage;

@Service
public class TwitchChatDashboardService {
    private final TwitchOAuthService oauthService;
    private final TwitchStreamSessionService sessionService;
    private final TwitchEventSubSubscriptionRegistry registry;
    private final TwitchChatMessageService messageService;

    public TwitchChatDashboardService(TwitchOAuthService oauthService, TwitchStreamSessionService sessionService,
            TwitchEventSubSubscriptionRegistry registry, TwitchChatMessageService messageService) {
        this.oauthService = oauthService;
        this.sessionService = sessionService;
        this.registry = registry;
        this.messageService = messageService;
    }

    public List<TwitchChatMessage> messages(int limit) {
        String broadcasterId = oauthService.connectedBroadcasterId();
        StreamSessionState state = sessionService.currentFor(broadcasterId);
        return messageService.latest(broadcasterId, state == null ? null : state.twitchStreamId(), limit);
    }

    public TwitchChatSummaryResponse summary() {
        String broadcasterId = oauthService.connectedBroadcasterId();
        StreamSessionState state = sessionService.currentFor(broadcasterId);
        String streamId = state == null ? null : state.twitchStreamId();
        String status = broadcasterId == null ? "disconnected"
                : registry.statusesFor(broadcasterId).get("channel.chat.message");
        boolean collecting = state != null && state.online() && "enabled".equals(status);
        return new TwitchChatSummaryResponse(collecting, status, streamId,
                messageService.count(broadcasterId, streamId));
    }
}
