package com.stream2song.backend.service;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.stream2song.backend.config.TwitchEventSubProperties;
import com.stream2song.backend.config.TwitchOAuthProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TwitchChatCollectionServiceTest {
    @Test
    void syncsAnAlreadyLiveSessionAgainstTwitchAndStartsChatForConfirmedStream() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer twitch = MockRestServiceServer.bindTo(restTemplate).build();
        TwitchEventSubSubscriptionRegistry registry = new TwitchEventSubSubscriptionRegistry();
        TwitchOAuthProperties oauth = new TwitchOAuthProperties("client-id", "client-secret",
                "http://localhost:8080/api/twitch/oauth/callback", "http://localhost:4200");
        TwitchEventSubProperties eventSub = new TwitchEventSubProperties(
                "https://tunnel.example/api/twitch/eventsub/webhook", "test-signing-secret-123");
        TwitchEventSubSubscriptionService subscriptions = new TwitchEventSubSubscriptionService(
                oauth, eventSub, registry, restTemplate);
        TwitchStreamSessionService sessions = new TwitchStreamSessionService();
        sessions.recordOnline("broadcaster-42", "stale-stream", Instant.parse("2026-10-26T09:00:00Z"));
        TwitchChatCollectionService collection = new TwitchChatCollectionService(
                subscriptions, sessions, () -> "broadcaster-42");

        twitch.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"access_token":"app-token","expires_in":3600,"token_type":"bearer"}
                        """, MediaType.APPLICATION_JSON));
        twitch.expect(requestTo("https://api.twitch.tv/helix/streams?user_id=broadcaster-42"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer app-token"))
                .andExpect(header("Client-Id", "client-id"))
                .andRespond(withSuccess("""
                        {"data":[{"id":"confirmed-stream","user_id":"broadcaster-42","started_at":"2026-10-26T10:00:00Z"}]}
                        """, MediaType.APPLICATION_JSON));
        twitch.expect(requestTo("https://api.twitch.tv/helix/eventsub/subscriptions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer app-token"))
                .andExpect(jsonPath("$.type").value("channel.chat.message"))
                .andExpect(jsonPath("$.condition.broadcaster_user_id").value("broadcaster-42"))
                .andExpect(jsonPath("$.condition.user_id").value("broadcaster-42"))
                .andRespond(withSuccess("""
                        {"data":[{"id":"chat-sub","status":"webhook_callback_verification_pending","type":"channel.chat.message"}]}
                        """, MediaType.APPLICATION_JSON));

        collection.synchronizeCurrentStream("broadcaster-42");

        assertThat(sessions.currentFor("broadcaster-42").online()).isTrue();
        assertThat(sessions.currentFor("broadcaster-42").twitchStreamId()).isEqualTo("confirmed-stream");
        assertThat(registry.statusesFor("broadcaster-42").get("channel.chat.message"))
                .isEqualTo("webhook_callback_verification_pending");
        twitch.verify();
    }
}
