package com.stream2song.backend.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import com.stream2song.backend.model.TwitchChatMessage;

@Service
public class TwitchChatMessageService {
    private final Map<String, TwitchChatMessage> messagesById = new ConcurrentHashMap<>();

    public enum RecordResult { STORED, DUPLICATE, INVALID }

    public RecordResult record(JsonNode event, String streamId, Instant receivedAt) {
        String id = text(event, "message_id");
        String broadcasterId = text(event, "broadcaster_user_id");
        String userId = text(event, "chatter_user_id");
        String username = text(event, "chatter_user_login");
        if (username == null) username = text(event, "chatter_user_name");
        JsonNode message = event.path("message");
        String content = text(message, "text");
        if (id == null || broadcasterId == null || username == null || content == null || streamId == null)
            return RecordResult.INVALID;
        TwitchChatMessage item = new TwitchChatMessage(id, broadcasterId, streamId, receivedAt, userId,
                username, content, text(event, "message_type"), text(event, "color"));
        return messagesById.putIfAbsent(id, item) == null ? RecordResult.STORED : RecordResult.DUPLICATE;
    }

    public List<TwitchChatMessage> latest(String broadcasterId, String streamId, int limit) {
        if (broadcasterId == null || streamId == null) return List.of();
        return messagesById.values().stream()
                .filter(message -> broadcasterId.equals(message.broadcasterId()) && streamId.equals(message.streamId()))
                .sorted(Comparator.comparing(TwitchChatMessage::timestamp).reversed())
                .limit(limit)
                .sorted(Comparator.comparing(TwitchChatMessage::timestamp))
                .toList();
    }

    public long count(String broadcasterId, String streamId) {
        if (broadcasterId == null || streamId == null) return 0;
        return messagesById.values().stream()
                .filter(message -> broadcasterId.equals(message.broadcasterId()) && streamId.equals(message.streamId()))
                .count();
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
