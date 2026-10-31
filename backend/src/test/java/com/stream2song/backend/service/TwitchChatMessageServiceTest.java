package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TwitchChatMessageServiceTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void latestUsesBoundedDeterministicSelectionForLargeTiedSessions() throws Exception {
        var forward = new TwitchChatMessageService();
        var reverse = new TwitchChatMessageService();
        Instant sameTime = Instant.parse("2026-10-30T12:00:00Z");
        int count = 30_000;
        for (int i=0; i<count; i++) record(forward, i, sameTime);
        for (int i=count-1; i>=0; i--) record(reverse, i, sameTime);

        var selected = forward.latest("owner", "stream", 17);
        var selectedFromReverse = reverse.latest("owner", "stream", 17);
        assertEquals(17, selected.size());
        assertEquals(selected.stream().map(item -> item.messageId()).toList(),
                selectedFromReverse.stream().map(item -> item.messageId()).toList());
        assertEquals(java.util.stream.IntStream.range(count-17, count).mapToObj(i -> String.format("msg-%05d", i)).toList(),
                selected.stream().map(item -> item.messageId()).toList());
        assertEquals(selected.stream().sorted(java.util.Comparator.comparing(
                com.stream2song.backend.model.TwitchChatMessage::timestamp)
                .thenComparing(com.stream2song.backend.model.TwitchChatMessage::messageId)).toList(), selected);
        assertTrue(forward.latest("owner", "stream", Integer.MAX_VALUE).size() <= 500);
        assertTrue(forward.latest("owner", "stream", 0).isEmpty());
    }

    @Test void nullTimestampsHaveStableOrderingWithoutBreakingLatestSelection() throws Exception {
        var service = new TwitchChatMessageService();
        record(service, 2, null);
        record(service, 1, null);
        record(service, 3, Instant.parse("2026-10-30T12:00:00Z"));
        assertEquals(java.util.List.of("msg-00001", "msg-00002", "msg-00003"),
                service.latest("owner", "stream", 3).stream().map(item -> item.messageId()).toList());
    }

    private void record(TwitchChatMessageService service, int index, Instant at) throws Exception {
        String id = String.format("msg-%05d", index);
        var event = mapper.readTree("{\"message_id\":\""+id+"\",\"broadcaster_user_id\":\"owner\",\"chatter_user_id\":\"user\",\"chatter_user_login\":\"viewer\",\"message\":{\"text\":\"hello\"}}");
        assertEquals(TwitchChatMessageService.RecordResult.STORED, service.record(event, "stream", at));
    }
}
