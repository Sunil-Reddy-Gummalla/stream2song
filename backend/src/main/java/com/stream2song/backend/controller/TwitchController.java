package com.stream2song.backend.controller;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import com.stream2song.backend.dto.TwitchConnectionStatusResponse;
import com.stream2song.backend.dto.TwitchStreamSessionResponse;
import com.stream2song.backend.dto.TwitchChatSummaryResponse;
import com.stream2song.backend.model.TwitchChatMessage;
import com.stream2song.backend.service.TwitchChatCollectionService;
import com.stream2song.backend.service.TwitchChatDashboardService;
import com.stream2song.backend.service.TwitchDashboardService;
import com.stream2song.backend.service.TwitchEventSubSubscriptionService;
import com.stream2song.backend.service.TwitchOAuthFailure;
import com.stream2song.backend.service.TwitchOAuthService;

@RestController
@RequestMapping("/api/twitch")
public class TwitchController {
    private final TwitchOAuthService twitchOAuthService;
    private final TwitchEventSubSubscriptionService subscriptionService;
    private final TwitchDashboardService dashboardService;
    private final TwitchChatCollectionService chatCollectionService;
    private final TwitchChatDashboardService chatDashboardService;

    public TwitchController(
            TwitchOAuthService twitchOAuthService,
            TwitchEventSubSubscriptionService subscriptionService,
            TwitchDashboardService dashboardService,
            TwitchChatCollectionService chatCollectionService,
            TwitchChatDashboardService chatDashboardService) {
        this.twitchOAuthService = twitchOAuthService;
        this.subscriptionService = subscriptionService;
        this.dashboardService = dashboardService;
        this.chatCollectionService = chatCollectionService;
        this.chatDashboardService = chatDashboardService;
    }

    @GetMapping("/connect")
    public ResponseEntity<Void> connect(HttpServletRequest request) {
        try {
            return redirect(twitchOAuthService.createAuthorizationUri(request.getSession(true).getId()));
        } catch (TwitchOAuthFailure failure) {
            return redirect(twitchOAuthService.frontendRedirect("error", failure.reason()));
        }
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            HttpServletRequest request) {
        try {
            String sessionId = request.getSession(false) == null ? null : request.getSession(false).getId();
            twitchOAuthService.completeAuthorization(state, code, error, sessionId);
            TwitchConnectionStatusResponse connection = twitchOAuthService.connectionStatus();
            subscriptionService.subscribeToStreamEvents(connection.broadcasterId());
            chatCollectionService.synchronizeCurrentStream(connection.broadcasterId());
            return redirect(twitchOAuthService.frontendRedirect("connected", null));
        } catch (TwitchOAuthFailure failure) {
            return redirect(twitchOAuthService.frontendRedirect("error", failure.reason()));
        }
    }

    @GetMapping("/status")
    public TwitchConnectionStatusResponse status() {
        return twitchOAuthService.connectionStatus();
    }

    @GetMapping("/session")
    public TwitchStreamSessionResponse session() {
        return dashboardService.currentSession();
    }

    @org.springframework.web.bind.annotation.PostMapping("/session/refresh")
    public TwitchStreamSessionResponse refreshSession() {
        TwitchConnectionStatusResponse connection = twitchOAuthService.connectionStatus();
        if (!connection.connected()) return TwitchStreamSessionResponse.disconnected();
        subscriptionService.subscribeToStreamEvents(connection.broadcasterId());
        chatCollectionService.synchronizeCurrentStream(connection.broadcasterId());
        return dashboardService.currentSession();
    }

    @GetMapping("/chat/messages")
    public java.util.List<TwitchChatMessage> chatMessages(
            @RequestParam(defaultValue = "20") int limit) {
        return chatDashboardService.messages(Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping("/chat/summary")
    public TwitchChatSummaryResponse chatSummary() {
        return chatDashboardService.summary();
    }

    private ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location.toASCIIString())
                .build();
    }
}
