package com.stream2song.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.stream2song.backend.config.TwitchEventSubProperties;
import com.stream2song.backend.model.StreamSessionState;

@Service
public class TwitchEventSubWebhookService {
    private static final Logger log = LoggerFactory.getLogger(TwitchEventSubWebhookService.class);
    private static final Duration MESSAGE_ID_RETENTION = Duration.ofHours(24);
    private static final String HMAC_PREFIX = "sha256=";

    private final TwitchEventSubProperties properties;
    private final TwitchBroadcasterProvider broadcasterProvider;
    private final TwitchStreamSessionService sessionService;
    private final TwitchEventSubSubscriptionRegistry subscriptionRegistry;
    private final TwitchChatMessageService chatMessageService;
    private final TwitchChatCollectionService chatCollectionService;
    private final ObjectMapper objectMapper;
    private final Map<String, Instant> processedMessageIds = new ConcurrentHashMap<>();

    public TwitchEventSubWebhookService(
            TwitchEventSubProperties properties,
            TwitchBroadcasterProvider broadcasterProvider,
            TwitchStreamSessionService sessionService,
            TwitchEventSubSubscriptionRegistry subscriptionRegistry,
            TwitchChatMessageService chatMessageService,
            TwitchChatCollectionService chatCollectionService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.broadcasterProvider = broadcasterProvider;
        this.sessionService = sessionService;
        this.subscriptionRegistry = subscriptionRegistry;
        this.chatMessageService = chatMessageService;
        this.chatCollectionService = chatCollectionService;
        this.objectMapper = objectMapper;
    }

    /** Returns a challenge for verification messages, or null when the request should receive 204. */
    public String handle(
            String messageId,
            String messageTimestamp,
            String signature,
            String messageType,
            byte[] rawBody) {
        if (!properties.isValidSigningSecret()) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "eventsub_signing_secret_missing");
        }
        if (blank(messageId) || blank(messageTimestamp) || blank(signature) || blank(messageType)
                || rawBody == null || rawBody.length == 0) {
            throw failure(HttpStatus.BAD_REQUEST, "eventsub_headers_or_body_missing");
        }
        if (!isValidSignature(messageId, messageTimestamp, signature, rawBody)) {
            throw failure(HttpStatus.FORBIDDEN, "eventsub_signature_invalid");
        }

        JsonNode root = parse(rawBody);
        JsonNode subscription = root.path("subscription");
        String eventType = text(subscription, "type");
        if (subscription.isMissingNode() || eventType == null) {
            throw failure(HttpStatus.BAD_REQUEST, "eventsub_subscription_invalid");
        }
        cleanupProcessedMessageIds();

        return switch (messageType) {
            case "webhook_callback_verification" -> handleChallenge(root, subscription);
            case "notification" -> {
                handleNotification(messageId, messageTimestamp, eventType, subscription, root.path("event"));
                yield null;
            }
            case "revocation" -> {
                handleRevocation(messageId, subscription);
                yield null;
            }
            default -> null; // Acknowledge valid but unsupported message types to prevent futile retries.
        };
    }

    private String handleChallenge(JsonNode root, JsonNode subscription) {
        String challenge = text(root, "challenge");
        if (challenge == null) {
            throw failure(HttpStatus.BAD_REQUEST, "eventsub_challenge_missing");
        }
        String id = text(subscription, "id");
        if (id != null) subscriptionRegistry.markVerified(id);
        return challenge;
    }

    private void handleNotification(
            String messageId, String messageTimestamp, String eventType, JsonNode subscription, JsonNode event) {
        Instant sentAt = parseTimestamp(messageTimestamp);
        String broadcasterId = text(event, "broadcaster_user_id");
        String subscribedBroadcasterId = text(subscription.path("condition"), "broadcaster_user_id");
        if (broadcasterId == null || subscribedBroadcasterId == null || !event.isObject()) {
            throw failure(HttpStatus.BAD_REQUEST, "eventsub_event_invalid");
        }

        Instant eventTimestamp;
        String streamId = null;
        if ("channel.chat.message".equals(eventType)) {
            if (processedMessageIds.putIfAbsent(messageId, Instant.now()) != null) return;
            String connectedBroadcasterId = broadcasterProvider.connectedBroadcasterId();
            String subscribedBotId = text(subscription.path("condition"), "user_id");
            String subscriptionId = text(subscription, "id");
            StreamSessionState state = sessionService.currentFor(broadcasterId);
            if (connectedBroadcasterId == null || !connectedBroadcasterId.equals(broadcasterId)
                    || !broadcasterId.equals(subscribedBroadcasterId) || !broadcasterId.equals(subscribedBotId)
                    || !subscriptionRegistry.isRegisteredActive(subscriptionId, broadcasterId, "channel.chat.message")
                    || state == null || !state.online() || state.twitchStreamId() == null) return;
            TwitchChatMessageService.RecordResult result = chatMessageService.record(event, state.twitchStreamId(), sentAt);
            if (result == TwitchChatMessageService.RecordResult.INVALID) {
                processedMessageIds.remove(messageId);
                throw failure(HttpStatus.BAD_REQUEST, "eventsub_chat_message_invalid");
            }
            return;
        }
        if ("stream.online".equals(eventType)) {
            streamId = text(event, "id");
            eventTimestamp = parseTimestamp(text(event, "started_at"));
            if (streamId == null) throw failure(HttpStatus.BAD_REQUEST, "eventsub_stream_id_missing");
        } else if ("stream.offline".equals(eventType)) {
            eventTimestamp = sentAt;
        } else {
            return;
        }

        if (processedMessageIds.putIfAbsent(messageId, Instant.now()) != null) return;

        String connectedBroadcasterId = broadcasterProvider.connectedBroadcasterId();
        if (connectedBroadcasterId == null
                || !connectedBroadcasterId.equals(broadcasterId)
                || !connectedBroadcasterId.equals(subscribedBroadcasterId)) {
            return;
        }

        try {
            if ("stream.online".equals(eventType)) {
                sessionService.recordOnline(broadcasterId, streamId, eventTimestamp);
                chatCollectionService.streamOnline(broadcasterId, streamId);
            } else {
                sessionService.recordOffline(broadcasterId, eventTimestamp);
                chatCollectionService.streamOffline(broadcasterId);
            }
        } catch (RuntimeException exception) {
            processedMessageIds.remove(messageId);
            throw exception;
        }
    }

    private void handleRevocation(String messageId, JsonNode subscription) {
        if (processedMessageIds.putIfAbsent(messageId, Instant.now()) == null) {
            String id = text(subscription, "id");
            String reason = text(subscription, "status");
            if (id == null || reason == null) {
                processedMessageIds.remove(messageId);
                throw failure(HttpStatus.BAD_REQUEST, "eventsub_revocation_invalid");
            }
            subscriptionRegistry.markRevoked(id, reason);
            log.info("Twitch EventSub subscription revoked: subscriptionId={} reason={}", id, reason);
        }
    }

    private void cleanupProcessedMessageIds() {
        Instant now = Instant.now();
        processedMessageIds.entrySet().removeIf(entry -> entry.getValue().plus(MESSAGE_ID_RETENTION).isBefore(now));
    }

    private JsonNode parse(byte[] rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (root == null || !root.isObject()) throw failure(HttpStatus.BAD_REQUEST, "eventsub_json_invalid");
            return root;
        } catch (JacksonException exception) {
            throw failure(HttpStatus.BAD_REQUEST, "eventsub_json_invalid");
        }
    }

    private Instant parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            throw failure(HttpStatus.BAD_REQUEST, "eventsub_timestamp_invalid");
        }
        try {
            return Instant.parse(timestamp);
        } catch (java.time.format.DateTimeParseException exception) {
            throw failure(HttpStatus.BAD_REQUEST, "eventsub_timestamp_invalid");
        }
    }

    private boolean isValidSignature(String id, String timestamp, String suppliedSignature, byte[] rawBody) {
        if (!suppliedSignature.matches("sha256=[0-9a-f]{64}")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.signingSecret().getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            mac.update(id.getBytes(StandardCharsets.UTF_8));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update(rawBody);
            String expected = HMAC_PREFIX + HexFormat.of().formatHex(mac.doFinal());
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    suppliedSignature.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "eventsub_signature_unavailable");
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private TwitchEventSubWebhookException failure(HttpStatus status, String reason) {
        return new TwitchEventSubWebhookException(status, reason);
    }
}
