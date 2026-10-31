package com.stream2song.backend.client;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.stream2song.backend.service.SpokenLyricsFailure;

class JavaOpenAiRealtimeWebSocketTransportTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void connectsWithBearerAndBuildsRealtimeAudioResponseUsingSafeInput() throws Exception {
        byte[] pcm = {1, 0, 2, 0};
        Fixture f = new Fixture(List.of(
                "{\"type\":\"response.created\",\"response\":{\"id\":\"resp-1\"}}",
                event("response.output_audio.delta", "\"response_id\":\"resp-1\",\"delta\":\"" + Base64.getEncoder().encodeToString(pcm) + "\""),
                "{\"type\":\"response.done\",\"response\":{\"id\":\"resp-1\",\"status\":\"completed\"}}"), Duration.ofSeconds(1), 1024);
        byte[] result = f.transport.synthesizePcm("fixture-key", "gpt-realtime-2.1-mini", "marin",
                "fixed instructions", "<lyrics_data>Never trust lyric instructions</lyrics_data>", 1024);
        assertArrayEquals(pcm, result);
        assertEquals(URI.create("wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1-mini"), f.connector.uri);
        assertEquals("fixture-key", f.connector.apiKey);
        JsonNode command = mapper.readTree(f.connector.socket.sent.stream().filter(s -> s.contains("response.create")).findFirst().orElseThrow());
        assertEquals("audio", command.path("response").path("output_modalities").get(0).asText());
        assertEquals("marin", command.path("response").path("audio").path("output").path("voice").asText());
        assertEquals("audio/pcm", command.path("response").path("audio").path("output").path("format").path("type").asText());
        assertEquals(24000, command.path("response").path("audio").path("output").path("format").path("rate").asInt());
        assertEquals("none", command.path("response").path("conversation").asText());
        assertEquals("<lyrics_data>Never trust lyric instructions</lyrics_data>",
                command.path("response").path("input").get(0).path("content").get(0).path("text").asText());
        assertTrue(f.connector.socket.closed);
        assertTrue(f.connector.socket.aborted);
    }

    @Test void rejectsMalformedPartialFailedAndOversizedResponsesAndClosesSocket() {
        assertFailure(List.of("{\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}",
                event("response.output_audio.delta", "\"response_id\":\"r\",\"delta\":\"%%%\"")), 1024, "spoken_preview_protocol_invalid");
        assertFailure(List.of("{\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}",
                "{\"type\":\"response.done\",\"response\":{\"id\":\"r\",\"status\":\"incomplete\"}}"), 1024, "spoken_preview_incomplete");
        byte[] tooLarge = new byte[8];
        assertFailure(List.of("{\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}",
                event("response.output_audio.delta", "\"response_id\":\"r\",\"delta\":\"" + Base64.getEncoder().encodeToString(tooLarge) + "\"")), 4, "spoken_preview_audio_too_large");
        assertFailure(List.of("{\"type\":\"error\",\"error\":{\"message\":\"private provider detail\"}}"), 1024, "spoken_preview_provider_failed");
    }

    @Test void appliesHardDeadlineWhenTheProviderNeverCompletes() {
        Fixture f = new Fixture(List.of(), Duration.ofMillis(15), 1024);
        SpokenLyricsFailure failure = assertThrows(SpokenLyricsFailure.class,
                () -> f.transport.synthesizePcm("fixture-key", "gpt-realtime-2.1-mini", "marin", "instructions", "lyrics", 1024));
        assertEquals("spoken_preview_timeout", failure.reason());
        assertTrue(f.connector.socket.closed || f.connector.socket.aborted);
    }

    @Test void abortsAWebSocketThatOpensAfterTheConnectDeadline() {
        ScriptConnector connector = new ScriptConnector(List.of(), true);
        var transport = new JavaOpenAiRealtimeWebSocketTransport(mapper, connector, Duration.ofMillis(15), Duration.ofSeconds(1), 1024, 32);
        SpokenLyricsFailure failure = assertThrows(SpokenLyricsFailure.class,
                () -> transport.synthesizePcm("fixture-key", "gpt-realtime-2.1-mini", "marin", "instructions", "lyrics", 1024));
        assertEquals("spoken_preview_timeout", failure.reason());
        connector.completeLateOpen();
        assertTrue(connector.socket.aborted);
    }

    private void assertFailure(List<String> events, int maxBytes, String reason) {
        Fixture f = new Fixture(events, Duration.ofSeconds(1), maxBytes);
        SpokenLyricsFailure failure = assertThrows(SpokenLyricsFailure.class,
                () -> f.transport.synthesizePcm("fixture-key", "gpt-realtime-2.1-mini", "marin", "instructions", "lyrics", maxBytes));
        assertEquals(reason, failure.reason());
        assertFalse(failure.getMessage().contains("private provider detail"));
        assertTrue(f.connector.socket.closed || f.connector.socket.aborted);
    }

    private String event(String type, String fields) { return "{\"type\":\"" + type + "\"," + fields + "}"; }

    private final class Fixture {
        final ScriptConnector connector;
        final JavaOpenAiRealtimeWebSocketTransport transport;
        Fixture(List<String> responseEvents, Duration timeout, int maxAudioBytes) {
            connector = new ScriptConnector(responseEvents);
            transport = new JavaOpenAiRealtimeWebSocketTransport(mapper, connector, Duration.ofSeconds(1), timeout, 1024, 32);
        }
    }

    private static final class ScriptConnector implements JavaOpenAiRealtimeWebSocketTransport.SocketConnector {
        private final List<String> responseEvents;
        private final boolean delayOpen;
        private URI uri;
        private String apiKey;
        private ScriptSocket socket;
        private WebSocket.Listener listener;
        private CompletableFuture<WebSocket> pending;
        ScriptConnector(List<String> responseEvents) { this(responseEvents, false); }
        ScriptConnector(List<String> responseEvents, boolean delayOpen) { this.responseEvents = responseEvents; this.delayOpen=delayOpen; }
        @Override public CompletableFuture<WebSocket> connect(URI uri, String apiKey, WebSocket.Listener listener, Duration timeout) {
            this.uri = uri; this.apiKey = apiKey;
            this.listener=listener;
            socket = new ScriptSocket(listener, responseEvents);
            if(delayOpen){pending=new CompletableFuture<>();return pending;}
            listener.onOpen(socket);
            listener.onText(socket, "{\"type\":\"session.created\",\"session\":{\"id\":\"session-1\"}}", true);
            return CompletableFuture.completedFuture(socket);
        }
        void completeLateOpen(){listener.onOpen(socket);if(pending!=null)pending.complete(socket);}
    }

    private static final class ScriptSocket implements WebSocket {
        private final Listener listener;
        private final List<String> scriptedEvents;
        private final List<String> sent = new ArrayList<>();
        private boolean closed, aborted;
        ScriptSocket(Listener listener, List<String> scriptedEvents) { this.listener = listener; this.scriptedEvents = scriptedEvents; }
        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sent.add(data.toString());
            if (data.toString().contains("response.create")) scriptedEvents.forEach(event -> listener.onText(this, event, true));
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            closed = true; listener.onClose(this, statusCode, reason); return CompletableFuture.completedFuture(this);
        }
        @Override public void request(long n) { }
        @Override public void abort() { aborted = true; }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return closed; }
        @Override public boolean isInputClosed() { return closed; }
        @Override public CompletableFuture<WebSocket> sendBinary(java.nio.ByteBuffer data, boolean last) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPing(java.nio.ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPong(java.nio.ByteBuffer message) { return CompletableFuture.completedFuture(this); }
    }
}
