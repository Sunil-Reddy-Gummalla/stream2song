package com.stream2song.backend.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.stream2song.backend.dto.TwitchConnectionStatusResponse;
import com.stream2song.backend.dto.TwitchStreamSessionResponse;
import com.stream2song.backend.model.StreamSessionState;

@Service
public class TwitchDashboardService {
    private final TwitchOAuthService oauthService;
    private final TwitchStreamSessionService sessionService;
    private final TwitchEventSubSubscriptionRegistry subscriptionRegistry;

    public TwitchDashboardService(
            TwitchOAuthService oauthService,
            TwitchStreamSessionService sessionService,
            TwitchEventSubSubscriptionRegistry subscriptionRegistry) {
        this.oauthService = oauthService;
        this.sessionService = sessionService;
        this.subscriptionRegistry = subscriptionRegistry;
    }

    public TwitchStreamSessionResponse currentSession() {
        TwitchConnectionStatusResponse connection = oauthService.connectionStatus();
        if (!connection.connected()) return TwitchStreamSessionResponse.disconnected();

        StreamSessionState state = sessionService.currentFor(connection.broadcasterId());
        return new TwitchStreamSessionResponse(
                true,
                connection.broadcasterId(),
                connection.login(),
                state != null && state.online(),
                state == null ? null : state.twitchStreamId(),
                state == null ? null : state.eventType(),
                state == null ? null : state.eventTimestamp(),
                subscriptionRegistry.statusesFor(connection.broadcasterId()));
    }
}
