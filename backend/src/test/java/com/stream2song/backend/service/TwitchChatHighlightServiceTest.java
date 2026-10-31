package com.stream2song.backend.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.stream2song.backend.dto.TwitchChatHighlightsResponse;
import com.stream2song.backend.model.TwitchChatMessage;
import com.stream2song.backend.service.TwitchStreamSessionService;

import static org.assertj.core.api.Assertions.assertThat;

class TwitchChatHighlightServiceTest {
    private static final Instant BASE = windowStart("2026-10-26T12:00:00Z", 30);
    private final TwitchChatHighlightService service = new TwitchChatHighlightService(null, null, null);

    @Test
    void ranksAReactionBurstAboveQuietWindows() {
        List<TwitchChatMessage> messages = new ArrayList<>();
        add(messages, "quiet-before", BASE.plusSeconds(1), "I see what happened", "viewer_one");
        add(messages, "quiet-before", BASE.plusSeconds(2), "I see what happened", "viewer_two");
        add(messages, "quiet-before", BASE.plusSeconds(3), "I see what happened", "viewer_three");
        for (int i = 0; i < 8; i++) {
            add(messages, "reaction-burst", BASE.plusSeconds(61L + i), "Pog!", "viewer_" + i);
        }
        add(messages, "quiet-after", BASE.plusSeconds(91), "I see what happened", "viewer_four");
        add(messages, "quiet-after", BASE.plusSeconds(92), "I see what happened", "viewer_five");
        add(messages, "quiet-after", BASE.plusSeconds(93), "I see what happened", "viewer_six");

        TwitchChatHighlightsResponse response = service.analyze(messages, "stream-1", 30, 10);

        assertThat(response.highlights()).hasSize(3);
        assertThat(response.highlights().getFirst().messageCount()).isEqualTo(8);
        assertThat(response.highlights().getFirst().summary()).contains("pog");
        assertThat(response.highlights().getFirst().score())
                .isGreaterThan(response.highlights().get(1).score());
        assertThat(response.highlights().getFirst().representativeMessages()).containsOnly("Pog!");
        assertThat(response.highlights().getFirst().explanation()).contains("chat-activity heuristic");
    }

    @Test
    void detectsRepeatedMeaningfulTermsAndExcludesUsernameAndUrl() {
        List<TwitchChatMessage> messages = List.of(
                message("1", BASE.plusSeconds(1), "streamer clutch play https://example.com", "streamer"),
                message("2", BASE.plusSeconds(2), "clutch clutch play", "streamer"),
                message("3", BASE.plusSeconds(3), "That was clutch play", "streamer"));

        TwitchChatHighlightsResponse response = service.analyze(messages, "stream-1", 30, 10);

        assertThat(response.highlights()).hasSize(1);
        assertThat(response.highlights().getFirst().summary()).contains("clutch", "play");
        assertThat(response.highlights().getFirst().summary()).doesNotContain("streamer", "example");
        assertThat(response.highlights().getFirst().explanation()).contains("recurring terms");
    }

    @Test
    void returnsClearEmptyAndInsufficientActivityResponses() {
        TwitchChatHighlightsResponse empty = service.analyze(List.of(), "stream-1", 30, 10);
        assertThat(empty.highlights()).isEmpty();
        assertThat(empty.message()).contains("No timestamped chat messages");
        assertThat(empty.heuristicOnly()).isTrue();

        TwitchChatHighlightsResponse tooFew = service.analyze(List.of(
                message("1", BASE.plusSeconds(1), "pog", "a"),
                message("2", BASE.plusSeconds(2), "pog", "b")), "stream-1", 30, 10);
        assertThat(tooFew.messagesAnalyzed()).isEqualTo(2);
        assertThat(tooFew.highlights()).isEmpty();
        assertThat(tooFew.message()).contains("Not enough chat activity");
    }

    @Test
    void partitionsMessagesAtWindowBoundariesAndOrdersTiesByTime() {
        List<TwitchChatMessage> messages = List.of(
                message("a1", BASE.plusSeconds(28), "clutch clutch", "one"),
                message("a2", BASE.plusSeconds(29), "clutch", "two"),
                message("a3", BASE.plusSeconds(29), "clutch", "three"),
                message("b1", BASE.plusSeconds(30), "comeback comeback", "four"),
                message("b2", BASE.plusSeconds(31), "comeback", "five"),
                message("b3", BASE.plusSeconds(32), "comeback", "six"));

        TwitchChatHighlightsResponse response = service.analyze(messages, "stream-1", 30, 10);

        assertThat(response.highlights()).hasSize(2);
        assertThat(response.highlights().get(0).startTime()).isBefore(response.highlights().get(1).startTime());
        assertThat(response.highlights().get(0).endTime()).isEqualTo(response.highlights().get(1).startTime());
        assertThat(response.highlights()).extracting(item -> item.messageCount()).containsExactly(3, 3);
    }

    @Test
    void ignoresMissingTimestampsAndDuplicateMessageIdsAndSuppressesAdjacentDuplicateMoments() {
        TwitchChatMessage repeated = message("same-id", BASE.plusSeconds(2), "Pog", "viewer");
        List<TwitchChatMessage> messages = List.of(
                repeated, repeated,
                message("3", BASE.plusSeconds(3), "Pog", "viewer"),
                message("4", BASE.plusSeconds(4), "Pog", "viewer"),
                message("5", BASE.plusSeconds(30), "Pog", "viewer"),
                message("6", BASE.plusSeconds(31), "Pog", "viewer"),
                message("7", BASE.plusSeconds(32), "Pog", "viewer"),
                new TwitchChatMessage("missing-time", "broadcaster", "stream-1", null,
                        "viewer", "viewer", "Pog", "text", null));

        TwitchChatHighlightsResponse response = service.analyze(messages, "stream-1", 30, 10);

        assertThat(response.messagesAnalyzed()).isEqualTo(6);
        assertThat(response.highlights()).hasSize(1);
        assertThat(response.highlights().getFirst().messageCount()).isEqualTo(3);
    }

    @Test
    void producesDeterministicRankingAndEvidence() {
        List<TwitchChatMessage> messages = List.of(
                message("2", BASE.plusSeconds(2), "clutch", "viewer_b"),
                message("1", BASE.plusSeconds(1), "clutch", "viewer_a"),
                message("3", BASE.plusSeconds(3), "clutch", "viewer_c"));

        TwitchChatHighlightsResponse first = service.analyze(messages, "stream-1", 30, 10);
        TwitchChatHighlightsResponse second = service.analyze(messages, "stream-1", 30, 10);

        assertThat(first).isEqualTo(second);
        assertThat(first.highlights().getFirst().representativeMessages()).containsOnly("clutch");
    }

    @Test
    void analyzesOnlyTheMostRecentlyCompletedSessionStream() throws Exception {
        TwitchStreamSessionService sessions = new TwitchStreamSessionService();
        sessions.recordOnline("broadcaster", "current-stream", BASE);
        sessions.recordOffline("broadcaster", BASE.plusSeconds(600));
        TwitchChatMessageService storedMessages = new TwitchChatMessageService();
        ObjectMapper mapper = new ObjectMapper();
        for (int i = 0; i < 3; i++) {
            Instant timestamp = BASE.plusSeconds(i + 1L);
            assertThat(storedMessages.record(chatEvent(mapper, "old-" + i, "clutch play"),
                    "old-stream", timestamp)).isEqualTo(TwitchChatMessageService.RecordResult.STORED);
            assertThat(storedMessages.record(chatEvent(mapper, "current-" + i, "clutch play"),
                    "current-stream", timestamp)).isEqualTo(TwitchChatMessageService.RecordResult.STORED);
        }
        TwitchChatHighlightService currentSessionService = new TwitchChatHighlightService(
                () -> "broadcaster", sessions, storedMessages);

        TwitchChatHighlightsResponse response = currentSessionService.currentStreamHighlights(30, 10);

        assertThat(response.streamId()).isEqualTo("current-stream");
        assertThat(response.messagesAnalyzed()).isEqualTo(3);
        assertThat(response.highlights()).hasSize(1);
    }

    private JsonNode chatEvent(ObjectMapper mapper, String id, String text) throws Exception {
        return mapper.readTree("""
                {"message_id":"%s","broadcaster_user_id":"broadcaster","chatter_user_id":"viewer-id",
                 "chatter_user_login":"viewer","message":{"text":"%s"},"message_type":"text"}
                """.formatted(id, text));
    }

    private void add(List<TwitchChatMessage> messages, String prefix, Instant timestamp, String text, String username) {
        messages.add(message(prefix + "-" + messages.size(), timestamp, text, username));
    }

    private TwitchChatMessage message(String id, Instant timestamp, String text, String username) {
        return new TwitchChatMessage(id, "broadcaster", "stream-1", timestamp,
                "user-" + username, username, text, "text", null);
    }

    private static Instant windowStart(String timestamp, int windowSeconds) {
        long seconds = Instant.parse(timestamp).getEpochSecond();
        return Instant.ofEpochSecond(Math.floorDiv(seconds, windowSeconds) * windowSeconds);
    }
}
