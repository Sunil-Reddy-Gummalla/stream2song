package com.stream2song.backend.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.stream2song.backend.config.TwitchEventSubProperties;
import com.stream2song.backend.model.StreamSessionState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwitchEventSubWebhookServiceTest {
    private static final String SECRET = "test-signing-secret-123";
    private static final String MESSAGE_TIMESTAMP = "2026-10-26T11:00:00Z";
    private TwitchEventSubSubscriptionRegistry registry;
    private TwitchStreamSessionService sessionService;
    private TwitchEventSubWebhookService webhookService;
    private TwitchChatMessageService chatMessages;

    @BeforeEach
    void setUp() {
        registry = new TwitchEventSubSubscriptionRegistry();
        sessionService = new TwitchStreamSessionService();
        org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
        TwitchEventSubSubscriptionService subscriptions = new TwitchEventSubSubscriptionService(
                new com.stream2song.backend.config.TwitchOAuthProperties("", "", "", ""),
                new TwitchEventSubProperties("", ""), registry, restTemplate);
        chatMessages = new TwitchChatMessageService();
        TwitchBroadcasterProvider provider = () -> "broadcaster-42";
        TwitchChatCollectionService collection = new TwitchChatCollectionService(subscriptions, sessionService, provider);
        webhookService = new TwitchEventSubWebhookService(
                new TwitchEventSubProperties("https://abc123.trycloudflare.com/api/twitch/eventsub/webhook", SECRET),
                provider,
                sessionService,
                registry,
                chatMessages,
                collection,
                new ObjectMapper());
    }

    @Test
    void verifiesChallengeAndMarksSubscriptionEnabled() {
        byte[] body = bytes("""
                {"challenge":"challenge-token","subscription":{"id":"subscription-1","type":"stream.online"}}
                """);

        String challenge = send("message-challenge", "webhook_callback_verification", body);
        registry.record("subscription-1", "broadcaster-42", "stream.online", "webhook_callback_verification_pending");

        assertThat(challenge).isEqualTo("challenge-token");
        assertThat(registry.statusesFor("broadcaster-42").get("stream.online")).isEqualTo("enabled");
    }

    @Test
    void recordsOnlineAndOfflineNotificationsAsCurrentSessionState() {
        byte[] online = bytes("""
                {"subscription":{"id":"online-sub","type":"stream.online","condition":{"broadcaster_user_id":"broadcaster-42"}},"event":{"id":"stream-99","broadcaster_user_id":"broadcaster-42","started_at":"2026-10-26T10:00:00Z"}}
                """);
        byte[] offline = bytes("""
                {"subscription":{"id":"offline-sub","type":"stream.offline","condition":{"broadcaster_user_id":"broadcaster-42"}},"event":{"broadcaster_user_id":"broadcaster-42"}}
                """);

        assertThat(send("message-online", "notification", online)).isNull();
        StreamSessionState live = sessionService.currentFor("broadcaster-42");
        assertThat(live.online()).isTrue();
        assertThat(live.twitchStreamId()).isEqualTo("stream-99");
        assertThat(live.eventType()).isEqualTo("stream.online");
        assertThat(live.eventTimestamp()).isEqualTo(Instant.parse("2026-10-26T10:00:00Z"));

        assertThat(send("message-offline", "notification", offline)).isNull();
        StreamSessionState ended = sessionService.currentFor("broadcaster-42");
        assertThat(ended.online()).isFalse();
        assertThat(ended.twitchStreamId()).isEqualTo("stream-99");
        assertThat(ended.eventType()).isEqualTo("stream.offline");
        assertThat(ended.eventTimestamp()).isEqualTo(Instant.parse(MESSAGE_TIMESTAMP));
        assertThat(registry.statusesFor("broadcaster-42").get("channel.chat.message")).isEqualTo("stopped");
    }

    @Test
    void acknowledgesDuplicateNotificationWithoutApplyingItAgain() {
        byte[] online = bytes("""
                {"subscription":{"id":"online-sub","type":"stream.online","condition":{"broadcaster_user_id":"broadcaster-42"}},"event":{"id":"stream-99","broadcaster_user_id":"broadcaster-42","started_at":"2026-10-26T10:00:00Z"}}
                """);
        byte[] offline = bytes("""
                {"subscription":{"id":"offline-sub","type":"stream.offline","condition":{"broadcaster_user_id":"broadcaster-42"}},"event":{"broadcaster_user_id":"broadcaster-42"}}
                """);
        send("message-online", "notification", online);
        send("message-offline", "notification", offline);

        assertThat(send("message-online", "notification", online)).isNull();
        assertThat(sessionService.currentFor("broadcaster-42").eventType()).isEqualTo("stream.offline");
        assertThat(sessionService.currentFor("broadcaster-42").online()).isFalse();
    }

    @Test
    void rejectsInvalidSignatureAndMalformedSignedJson() {
        byte[] body = bytes("{} ");
        assertThatThrownBy(() -> webhookService.handle(
                "bad-signature", MESSAGE_TIMESTAMP, "sha256=" + "0".repeat(64), "notification", body))
                .isInstanceOf(TwitchEventSubWebhookException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);

        byte[] malformed = bytes("{not-json");
        assertThatThrownBy(() -> send("malformed-json", "notification", malformed))
                .isInstanceOf(TwitchEventSubWebhookException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsMissingHeadersAndEventFields() {
        assertThatThrownBy(() -> webhookService.handle(null, MESSAGE_TIMESTAMP, "signature", "notification", bytes("{}")))
                .isInstanceOf(TwitchEventSubWebhookException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        byte[] malformedEvent = bytes("""
                {"subscription":{"type":"stream.online"},"event":{"broadcaster_user_id":"broadcaster-42"}}
                """);
        assertThatThrownBy(() -> send("missing-stream-id", "notification", malformedEvent))
                .isInstanceOf(TwitchEventSubWebhookException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void recordsSubscriptionRevocationWithoutChangingStreamState() {
        registry.record("offline-sub", "broadcaster-42", "stream.offline", "enabled");
        byte[] body = bytes("""
                {"subscription":{"id":"offline-sub","type":"stream.offline","status":"authorization_revoked","condition":{"broadcaster_user_id":"broadcaster-42"}}}
                """);

        send("revocation-message", "revocation", body);

        assertThat(registry.statusesFor("broadcaster-42").get("stream.offline"))
                .isEqualTo("revoked:authorization_revoked");
        assertThat(sessionService.currentFor("broadcaster-42")).isNull();
    }

    @Test
    void recordsChatMessageOnceOnlyForConnectedActiveStream() {
        sessionService.recordOnline("broadcaster-42", "stream-99", Instant.parse("2026-10-26T10:00:00Z"));
        registry.record("chat-subscription", "broadcaster-42", "channel.chat.message", "enabled");
        byte[] chat = bytes("""
                {"subscription":{"id":"chat-subscription","type":"channel.chat.message","condition":{"broadcaster_user_id":"broadcaster-42","user_id":"broadcaster-42"}},"event":{"broadcaster_user_id":"broadcaster-42","chatter_user_id":"viewer-1","chatter_user_login":"viewer_one","message_id":"chat-message-1","message":{"text":"hello stream"},"message_type":"text","color":"#AABBCC"}}
                """);
        send("chat-delivery-1", "notification", chat);
        // Retry under another EventSub envelope ID still deduplicates by Twitch message ID.
        send("chat-delivery-2", "notification", chat);
        assertThat(chatMessages.latest("broadcaster-42", "stream-99", 20)).hasSize(1)
                .first().satisfies(message -> {
                    assertThat(message.username()).isEqualTo("viewer_one");
                    assertThat(message.text()).isEqualTo("hello stream");
                    assertThat(message.messageId()).isEqualTo("chat-message-1");
                    assertThat(message.streamId()).isEqualTo("stream-99");
                    assertThat(message.messageType()).isEqualTo("text");
                    assertThat(message.timestamp()).isEqualTo(Instant.parse(MESSAGE_TIMESTAMP));
                });
    }

    @Test
    void doesNotStoreChatWhenStreamIsOffline() {
        byte[] chat = bytes("""
                {"subscription":{"type":"channel.chat.message","condition":{"broadcaster_user_id":"broadcaster-42","user_id":"broadcaster-42"}},"event":{"broadcaster_user_id":"broadcaster-42","chatter_user_login":"viewer_one","message_id":"chat-message-2","message":{"text":"hello"}}}
                """);
        send("chat-offline", "notification", chat);
        assertThat(chatMessages.count("broadcaster-42", "stream-99")).isZero();
    }

    private String send(String id, String type, byte[] body) {
        return webhookService.handle(id, MESSAGE_TIMESTAMP, signature(id, MESSAGE_TIMESTAMP, body), type, body);
    }

    private String signature(String id, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            mac.update(id.getBytes(StandardCharsets.UTF_8));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
