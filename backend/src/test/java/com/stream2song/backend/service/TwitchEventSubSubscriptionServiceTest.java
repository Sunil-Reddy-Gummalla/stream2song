package com.stream2song.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.stream2song.backend.config.TwitchEventSubProperties;
import com.stream2song.backend.config.TwitchOAuthProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.ACCEPTED;

class TwitchEventSubSubscriptionServiceTest {
    private static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String SIGNING_SECRET = "unit-test-signing-secret";
    private static final String CALLBACK_URL = "https://stream2song.trycloudflare.com/api/twitch/eventsub/webhook";
    private RestTemplate restTemplate;
    private MockRestServiceServer twitchServer;
    private TwitchEventSubSubscriptionRegistry registry;
    private TwitchEventSubSubscriptionService service;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        twitchServer = MockRestServiceServer.bindTo(restTemplate).build();
        registry = new TwitchEventSubSubscriptionRegistry();
        service = new TwitchEventSubSubscriptionService(
                new TwitchOAuthProperties(CLIENT_ID, CLIENT_SECRET,
                        "http://localhost:8080/api/twitch/oauth/callback", "http://localhost:4200"),
                new TwitchEventSubProperties(CALLBACK_URL, SIGNING_SECRET), registry, restTemplate);
    }

    @Test
    void obtainsAppTokenAndCreatesOnlineAndOfflineWebhookSubscriptions() {
        twitchServer.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("grant_type=client_credentials"),
                        org.hamcrest.Matchers.containsString("client_id=" + CLIENT_ID),
                        org.hamcrest.Matchers.containsString("client_secret=" + CLIENT_SECRET))))
                .andRespond(withSuccess("""
                        {"access_token":"test-app-token","expires_in":3600,"token_type":"bearer"}
                        """, MediaType.APPLICATION_JSON));
        expectSubscription("stream.online", "subscription-online");
        expectSubscription("stream.offline", "subscription-offline");

        service.subscribeToStreamEvents("broadcaster-42");
        // A retry while the webhook challenge is pending must not create duplicate subscriptions.
        service.subscribeToStreamEvents("broadcaster-42");

        assertThat(registry.statusesFor("broadcaster-42"))
                .containsEntry("stream.online", "webhook_callback_verification_pending")
                .containsEntry("stream.offline", "webhook_callback_verification_pending");
        twitchServer.verify();
    }

    @Test
    void skipsSubscriptionsWhenThePublicHttpsCallbackOrSecretIsNotConfigured() {
        TwitchEventSubSubscriptionService unconfigured = new TwitchEventSubSubscriptionService(
                new TwitchOAuthProperties(CLIENT_ID, CLIENT_SECRET, "redirect", "frontend"),
                new TwitchEventSubProperties("http://localhost:8080/api/twitch/eventsub/webhook", "short"),
                registry,
                restTemplate);

        unconfigured.subscribeToStreamEvents("broadcaster-42");

        assertThat(registry.statusesFor("broadcaster-42"))
                .containsEntry("stream.online", "not_configured")
                .containsEntry("stream.offline", "not_configured");
        assertThat(new TwitchEventSubProperties("http://localhost:8080/api/twitch/eventsub/webhook", "short")
                .configurationProblem()).contains("TWITCH_EVENTSUB_CALLBACK_URL", "TWITCH_EVENTSUB_SIGNING_SECRET");
        twitchServer.verify();
    }

    @Test
    void marksStreamSubscriptionsFailedWhenTwitchRejectsTheAppToken() {
        twitchServer.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        service.subscribeToStreamEvents("broadcaster-42");

        assertThat(registry.statusesFor("broadcaster-42"))
                .containsEntry("stream.online", "failed")
                .containsEntry("stream.offline", "failed");
        twitchServer.verify();
    }

    @Test
    void marksStreamSubscriptionsFailedWhenTwitchRejectsSubscriptionCreation() {
        twitchServer.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andRespond(withSuccess("""
                        {"access_token":"test-app-token","expires_in":3600,"token_type":"bearer"}
                        """, MediaType.APPLICATION_JSON));
        twitchServer.expect(requestTo("https://api.twitch.tv/helix/eventsub/subscriptions"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));
        twitchServer.expect(requestTo("https://api.twitch.tv/helix/eventsub/subscriptions"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));

        service.subscribeToStreamEvents("broadcaster-42");

        assertThat(registry.statusesFor("broadcaster-42"))
                .containsEntry("stream.online", "failed")
                .containsEntry("stream.offline", "failed");
        twitchServer.verify();
    }

    @Test
    void createsChatSubscriptionWithBroadcasterAndAuthorizedChatUserThenDeletesItOnOffline() {
        twitchServer.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"access_token":"test-app-token","expires_in":3600,"token_type":"bearer"}
                        """, MediaType.APPLICATION_JSON));
        twitchServer.expect(requestTo("https://api.twitch.tv/helix/eventsub/subscriptions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-app-token"))
                .andExpect(header("Client-Id", CLIENT_ID))
                .andExpect(jsonPath("$.type").value("channel.chat.message"))
                .andExpect(jsonPath("$.condition.broadcaster_user_id").value("broadcaster-42"))
                .andExpect(jsonPath("$.condition.user_id").value("broadcaster-42"))
                .andRespond(withStatus(ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"data":[{"id":"chat-subscription","status":"enabled","type":"channel.chat.message"}]}
                        """));
        twitchServer.expect(requestTo("https://api.twitch.tv/helix/eventsub/subscriptions?id=chat-subscription"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer test-app-token"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NO_CONTENT));

        service.subscribeToChatMessages("broadcaster-42", "broadcaster-42");

        service.stopChatMessages("broadcaster-42");

        assertThat(registry.statusesFor("broadcaster-42")).containsEntry("channel.chat.message", "stopped");
        twitchServer.verify();
    }

    @Test
    void getsCurrentLiveStreamForBroadcasterUsingServerAppToken() {
        twitchServer.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andRespond(withSuccess("""
                        {"access_token":"test-app-token","expires_in":3600,"token_type":"bearer"}
                        """, MediaType.APPLICATION_JSON));
        twitchServer.expect(requestTo("https://api.twitch.tv/helix/streams?user_id=broadcaster-42"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-app-token"))
                .andExpect(header("Client-Id", CLIENT_ID))
                .andRespond(withSuccess("""
                        {"data":[{"id":"stream-99","user_id":"broadcaster-42","started_at":"2026-10-26T10:00:00Z"}]}
                        """, MediaType.APPLICATION_JSON));

        var active = service.activeStream("broadcaster-42");

        assertThat(active.id()).isEqualTo("stream-99");
        assertThat(active.startedAt()).isEqualTo("2026-10-26T10:00:00Z");
        twitchServer.verify();
    }

    @Test
    void marksLiveStreamLookupFailedWhenTwitchReturnsAnApiError() {
        twitchServer.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andRespond(withSuccess("""
                        {"access_token":"test-app-token","expires_in":3600,"token_type":"bearer"}
                        """, MediaType.APPLICATION_JSON));
        twitchServer.expect(requestTo("https://api.twitch.tv/helix/streams?user_id=broadcaster-42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThat(service.activeStream("broadcaster-42")).isNull();
        assertThat(registry.statusesFor("broadcaster-42"))
                .containsEntry("channel.chat.message", "failed");
        twitchServer.verify();
    }

    @Test
    void marksChatAuthorizationFailureWithoutExposingTwitchErrorDetails() {
        twitchServer.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andRespond(withSuccess("""
                        {"access_token":"test-app-token","expires_in":3600,"token_type":"bearer"}
                        """, MediaType.APPLICATION_JSON));
        twitchServer.expect(requestTo("https://api.twitch.tv/helix/eventsub/subscriptions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));

        service.subscribeToChatMessages("broadcaster-42", "broadcaster-42");

        assertThat(registry.statusesFor("broadcaster-42"))
                .containsEntry("channel.chat.message", "authorization_required");
        twitchServer.verify();
    }

    private void expectSubscription(String eventType, String subscriptionId) {
        twitchServer.expect(requestTo("https://api.twitch.tv/helix/eventsub/subscriptions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-app-token"))
                .andExpect(header("Client-Id", CLIENT_ID))
                .andExpect(jsonPath("$.type").value(eventType))
                .andExpect(jsonPath("$.version").value("1"))
                .andExpect(jsonPath("$.condition.broadcaster_user_id").value("broadcaster-42"))
                .andExpect(jsonPath("$.transport.method").value("webhook"))
                .andExpect(jsonPath("$.transport.callback").value(CALLBACK_URL))
                .andExpect(jsonPath("$.transport.secret").value(SIGNING_SECRET))
                .andRespond(withStatus(ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"data":[{"id":"%s","status":"webhook_callback_verification_pending","type":"%s"}]}
                        """.formatted(subscriptionId, eventType)));
    }
}
