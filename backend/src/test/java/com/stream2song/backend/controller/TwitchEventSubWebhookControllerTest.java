package com.stream2song.backend.controller;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.stream2song.backend.config.TwitchEventSubProperties;
import com.stream2song.backend.service.TwitchEventSubSubscriptionRegistry;
import com.stream2song.backend.service.TwitchEventSubSubscriptionService;
import com.stream2song.backend.service.TwitchEventSubWebhookService;
import com.stream2song.backend.service.TwitchStreamSessionService;
import com.stream2song.backend.service.TwitchBroadcasterProvider;
import com.stream2song.backend.service.TwitchChatMessageService;
import com.stream2song.backend.service.TwitchChatCollectionService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TwitchEventSubWebhookControllerTest {
    private static final String SECRET = "controller-test-secret";
    private static final String MESSAGE_ID = "controller-challenge-id";
    private static final String TIMESTAMP = "2026-10-26T11:00:00Z";
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TwitchStreamSessionService sessions = new TwitchStreamSessionService();
        TwitchEventSubSubscriptionRegistry registry = new TwitchEventSubSubscriptionRegistry();
        org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
        com.stream2song.backend.config.TwitchOAuthProperties oauthProperties =
                new com.stream2song.backend.config.TwitchOAuthProperties("", "", "", "");
        TwitchEventSubSubscriptionService subscriptions = new TwitchEventSubSubscriptionService(
                oauthProperties, new TwitchEventSubProperties("", ""), registry,
                new com.stream2song.backend.service.TwitchAppAccessTokenService(oauthProperties, restTemplate), restTemplate);
        TwitchBroadcasterProvider provider = () -> "broadcaster-42";
        TwitchEventSubWebhookService service = new TwitchEventSubWebhookService(
                new TwitchEventSubProperties("https://stream2song.example/api/twitch/eventsub/webhook", SECRET),
                provider,
                sessions,
                registry,
                new TwitchChatMessageService(),
                new TwitchChatCollectionService(subscriptions, sessions, provider),
                new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new TwitchEventSubWebhookController(service)).build();
    }

    @Test
    void returnsTheRawChallengeAsPlainText() throws Exception {
        byte[] body = "{\"challenge\":\"only-this-challenge\",\"subscription\":{\"type\":\"stream.online\"}}"
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/twitch/eventsub/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Twitch-Eventsub-Message-Id", MESSAGE_ID)
                        .header("Twitch-Eventsub-Message-Timestamp", TIMESTAMP)
                        .header("Twitch-Eventsub-Message-Signature", signature(body))
                        .header("Twitch-Eventsub-Message-Type", "webhook_callback_verification")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("only-this-challenge"))
                .andExpect(header().string("Content-Length", Integer.toString("only-this-challenge".length())));
    }

    @Test
    void rejectsMalformedWebhookRequestsWithClientErrors() throws Exception {
        mockMvc.perform(post("/api/twitch/eventsub/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/twitch/eventsub/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Twitch-Eventsub-Message-Id", MESSAGE_ID)
                        .header("Twitch-Eventsub-Message-Timestamp", TIMESTAMP)
                        .header("Twitch-Eventsub-Message-Signature", "sha256=" + "0".repeat(64))
                        .header("Twitch-Eventsub-Message-Type", "notification")
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Content-Type"));
    }

    private String signature(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            mac.update(MESSAGE_ID.getBytes(StandardCharsets.UTF_8));
            mac.update(TIMESTAMP.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
