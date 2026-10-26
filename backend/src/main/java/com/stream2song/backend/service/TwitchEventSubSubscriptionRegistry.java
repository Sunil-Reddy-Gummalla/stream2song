package com.stream2song.backend.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class TwitchEventSubSubscriptionRegistry {
    private final Map<String, Subscription> byId = new ConcurrentHashMap<>();
    private final Map<String, String> statusByBroadcasterAndType = new ConcurrentHashMap<>();
    private final java.util.Set<String> verifiedBeforeRegistration = ConcurrentHashMap.newKeySet();

    public boolean isActive(String broadcasterId, String eventType) {
        String status = statusByBroadcasterAndType.get(key(broadcasterId, eventType));
        return "enabled".equals(status) || "webhook_callback_verification_pending".equals(status);
    }

    public boolean isRegisteredActive(String id, String broadcasterId, String eventType) {
        if (id == null || broadcasterId == null || eventType == null) return false;
        Subscription subscription = byId.get(id);
        return subscription != null && subscription.broadcasterId().equals(broadcasterId)
                && subscription.eventType().equals(eventType) && isActive(broadcasterId, eventType);
    }

    public void record(String id, String broadcasterId, String eventType, String status) {
        byId.put(id, new Subscription(broadcasterId, eventType));
        statusByBroadcasterAndType.put(key(broadcasterId, eventType),
                verifiedBeforeRegistration.remove(id) ? "enabled" : status);
    }

    public void markVerified(String id) {
        Subscription subscription = byId.get(id);
        if (subscription != null) {
            statusByBroadcasterAndType.put(key(subscription.broadcasterId(), subscription.eventType()), "enabled");
        } else {
            // Twitch can deliver the verification challenge before the create request returns its ID.
            verifiedBeforeRegistration.add(id);
        }
    }

    public void markRevoked(String id, String reason) {
        Subscription subscription = byId.get(id);
        if (subscription != null) {
            statusByBroadcasterAndType.put(key(subscription.broadcasterId(), subscription.eventType()),
                    "revoked:" + sanitizeStatus(reason));
            byId.remove(id);
        }
    }

    public void markStatus(String broadcasterId, String eventType, String status) {
        statusByBroadcasterAndType.put(key(broadcasterId, eventType), status);
    }

    public String subscriptionIdFor(String broadcasterId, String eventType) {
        return byId.entrySet().stream()
                .filter(entry -> entry.getValue().broadcasterId().equals(broadcasterId)
                        && entry.getValue().eventType().equals(eventType))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    public void remove(String id) {
        Subscription subscription = byId.remove(id);
        if (subscription != null) statusByBroadcasterAndType.remove(key(subscription.broadcasterId(), subscription.eventType()));
    }

    public void markStatusForSubscription(String id, String status) {
        Subscription subscription = byId.get(id);
        if (subscription != null) markStatus(subscription.broadcasterId(), subscription.eventType(), status);
    }

    public Map<String, String> statusesFor(String broadcasterId) {
        if (broadcasterId == null) return Map.of();
        return Map.of(
                "stream.online", statusByBroadcasterAndType.getOrDefault(key(broadcasterId, "stream.online"), "not_subscribed"),
                "stream.offline", statusByBroadcasterAndType.getOrDefault(key(broadcasterId, "stream.offline"), "not_subscribed"),
                "channel.chat.message", statusByBroadcasterAndType.getOrDefault(key(broadcasterId, "channel.chat.message"), "awaiting_stream"));
    }

    private String key(String broadcasterId, String eventType) {
        return broadcasterId + ":" + eventType;
    }

    private String sanitizeStatus(String status) {
        if (status == null || !status.matches("[a-z_]{1,64}")) return "unknown";
        return status;
    }

    private record Subscription(String broadcasterId, String eventType) { }
}
