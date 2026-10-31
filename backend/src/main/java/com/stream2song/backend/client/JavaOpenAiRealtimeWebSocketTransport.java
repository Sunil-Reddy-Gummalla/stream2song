package com.stream2song.backend.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.stream2song.backend.service.OpenAiRealtimeWebSocketTransport;
import com.stream2song.backend.service.SpokenLyricsFailure;

@Component
public class JavaOpenAiRealtimeWebSocketTransport implements OpenAiRealtimeWebSocketTransport {
    static final int EVENT_CHAR_LIMIT = 512 * 1024;
    static final int EVENT_QUEUE_LIMIT = 32;
    static final int EVENT_COUNT_LIMIT = 2048;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(60);
    private static final URI ENDPOINT = URI.create("wss://api.openai.com/v1/realtime");
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private final ObjectMapper mapper;
    private final SocketConnector connector;

    @Autowired
    public JavaOpenAiRealtimeWebSocketTransport(ObjectMapper mapper) {
        this(mapper, new JdkSocketConnector(), CONNECT_TIMEOUT, TOTAL_TIMEOUT, EVENT_CHAR_LIMIT, EVENT_COUNT_LIMIT);
    }

    JavaOpenAiRealtimeWebSocketTransport(ObjectMapper mapper, SocketConnector connector, Duration connectTimeout,
            Duration totalTimeout, int eventCharLimit, int eventCountLimit) {
        this.mapper = mapper;
        this.connector = connector;
        this.connectTimeout = connectTimeout;
        this.totalTimeout = totalTimeout;
        this.eventCharLimit = eventCharLimit;
        this.eventCountLimit = eventCountLimit;
    }

    private final Duration connectTimeout;
    private final Duration totalTimeout;
    private final int eventCharLimit;
    private final int eventCountLimit;

    @Override public byte[] synthesizePcm(String apiKey, String model, String voice, String instructions, String lyrics,
            int maxAudioBytes) {
        if (apiKey == null || apiKey.isBlank() || model == null || !MODEL.matcher(model).matches()
                || voice == null || instructions == null || lyrics == null || maxAudioBytes < 2)
            throw new SpokenLyricsFailure(503, "spoken_preview_not_configured");
        long deadline = System.nanoTime() + totalTimeout.toNanos();
        ArrayBlockingQueue<Inbound> incoming = new ArrayBlockingQueue<>(EVENT_QUEUE_LIMIT);
        RealtimeListener listener = new RealtimeListener(incoming, eventCharLimit);
        WebSocket socket = null;
        java.util.concurrent.CompletableFuture<WebSocket> connection = null;
        String responseId = null;
        boolean responseRequested = false;
        boolean success = false;
        try {
            URI uri = URI.create(ENDPOINT + "?model=" + java.net.URLEncoder.encode(model, StandardCharsets.UTF_8));
            connection = connector.connect(uri, apiKey, listener, connectTimeout);
            socket = connection.get(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
            ByteAudio audio = new ByteAudio(maxAudioBytes);
            boolean responseCreated = false;
            for (int count = 0; count < eventCountLimit; count++) {
                Inbound message = next(incoming, deadline);
                if (message.failureReason() != null) throw new SpokenLyricsFailure(502, message.failureReason());
                if (message.closed()) throw new SpokenLyricsFailure(502, "spoken_preview_provider_failed");
                JsonNode event = parseEvent(message.text());
                String type = event.path("type").asText();
                switch (type) {
                    case "session.created" -> {
                        if (responseRequested || !event.path("session").path("id").isTextual()
                                || event.path("session").path("id").asText().isBlank()) throw protocolFailure();
                        send(socket, responseCreate(model, voice, instructions, lyrics), deadline);
                        responseRequested = true;
                    }
                    case "response.created" -> {
                        JsonNode idNode = event.path("response").path("id");
                        String id = idNode.isTextual() ? idNode.asText() : null;
                        if (!responseRequested || responseCreated || id == null || id.isBlank()) throw protocolFailure();
                        responseId = id;
                        responseCreated = true;
                    }
                    case "response.output_audio.delta" -> {
                        JsonNode responseIdNode = event.path("response_id"), deltaNode = event.path("delta");
                        String eventResponseId = responseIdNode.isTextual() ? responseIdNode.asText() : null;
                        String delta = deltaNode.isTextual() ? deltaNode.asText() : null;
                        if (!responseCreated || !responseId.equals(eventResponseId) || delta == null || delta.isEmpty()
                                || delta.length() > ((maxAudioBytes + 2L) / 3L) * 4L + 4L) throw protocolFailure();
                        byte[] pcm;
                        try { pcm = Base64.getDecoder().decode(delta); }
                        catch (IllegalArgumentException invalid) { throw protocolFailure(); }
                        if (pcm.length == 0 || (pcm.length & 1) != 0) throw protocolFailure();
                        audio.append(pcm);
                    }
                    case "response.done" -> {
                        JsonNode response = event.path("response");
                        JsonNode idNode = response.path("id"), statusNode = response.path("status");
                        String id = idNode.isTextual() ? idNode.asText() : null;
                        String status = statusNode.isTextual() ? statusNode.asText() : null;
                        if (!responseCreated || !responseId.equals(id) || !"completed".equals(status) || audio.size() == 0)
                            throw new SpokenLyricsFailure(502, "spoken_preview_incomplete");
                        success = true;
                        return audio.bytes();
                    }
                    case "error" -> throw new SpokenLyricsFailure(502, "spoken_preview_provider_failed");
                    default -> { /* Realtime emits lifecycle and transcript events that do not carry audio. */ }
                }
            }
            throw new SpokenLyricsFailure(502, "spoken_preview_protocol_invalid");
        } catch (SpokenLyricsFailure failure) {
            throw failure;
        } catch (java.util.concurrent.TimeoutException timeout) {
            abandon(connection, listener);
            throw new SpokenLyricsFailure(504, "spoken_preview_timeout");
        } catch (InterruptedException interrupted) {
            abandon(connection, listener);
            Thread.currentThread().interrupt();
            throw new SpokenLyricsFailure(504, "spoken_preview_timeout");
        } catch (Exception failure) {
            if (socket == null) abandon(connection, listener);
            throw new SpokenLyricsFailure(502, "spoken_preview_provider_failed");
        } finally {
            if (socket != null) close(socket, responseId, !success && responseRequested);
        }
    }

    private JsonNode parseEvent(String text) {
        if (text == null || text.isBlank() || text.length() > eventCharLimit) throw protocolFailure();
        try {
            JsonNode event = mapper.readTree(text);
            if (event == null || !event.isObject() || !event.path("type").isTextual()) throw protocolFailure();
            return event;
        } catch (SpokenLyricsFailure failure) { throw failure; }
        catch (Exception invalid) { throw protocolFailure(); }
    }

    private String responseCreate(String model, String voice, String instructions, String lyrics) {
        try {
            return mapper.writeValueAsString(Map.of("type", "response.create", "response", Map.of(
                    "instructions", instructions,
                    "tools", java.util.List.of(),
                    "conversation", "none",
                    "output_modalities", java.util.List.of("audio"),
                    "audio", Map.of("output", Map.of("format", Map.of("type", "audio/pcm", "rate", 24000), "voice", voice)),
                    "max_output_tokens", 4096,
                    "input", java.util.List.of(Map.of("type", "message", "role", "user", "content",
                            java.util.List.of(Map.of("type", "input_text", "text", lyrics)))))));
        } catch (Exception impossible) { throw new SpokenLyricsFailure(502, "spoken_preview_protocol_invalid"); }
    }

    private Inbound next(ArrayBlockingQueue<Inbound> queue, long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SpokenLyricsFailure(504, "spoken_preview_timeout");
        Inbound event = queue.poll(remaining, TimeUnit.NANOSECONDS);
        if (event == null) throw new SpokenLyricsFailure(504, "spoken_preview_timeout");
        return event;
    }

    private void send(WebSocket socket, String event, long deadline) throws Exception {
        long remaining = Math.min(deadline - System.nanoTime(), TimeUnit.SECONDS.toNanos(5));
        if (remaining <= 0) throw new SpokenLyricsFailure(504, "spoken_preview_timeout");
        socket.sendText(event, true).toCompletableFuture().get(remaining, TimeUnit.NANOSECONDS);
    }

    private void close(WebSocket socket, String responseId, boolean cancel) {
        try {
            if (cancel) {
                String event = responseId == null
                        ? mapper.writeValueAsString(Map.of("type", "response.cancel"))
                        : mapper.writeValueAsString(Map.of("type", "response.cancel", "response_id", responseId));
                socket.sendText(event, true).toCompletableFuture().get(250, TimeUnit.MILLISECONDS);
            }
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").toCompletableFuture().get(250, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            socket.abort();
        }
    }

    private void abandon(java.util.concurrent.CompletableFuture<WebSocket> connection, RealtimeListener listener) {
        listener.abandon();
        if (connection != null) {
            connection.whenComplete((lateSocket, error) -> { if (lateSocket != null) lateSocket.abort(); });
            connection.cancel(true);
        }
    }

    private SpokenLyricsFailure protocolFailure() { return new SpokenLyricsFailure(502, "spoken_preview_protocol_invalid"); }

    private static final class ByteAudio {
        private final int maxBytes;
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        ByteAudio(int maxBytes) { this.maxBytes = maxBytes; }
        void append(byte[] chunk) {
            if (bytes.size() + (long) chunk.length > maxBytes) throw new SpokenLyricsFailure(413, "spoken_preview_audio_too_large");
            bytes.writeBytes(chunk);
        }
        int size() { return bytes.size(); }
        byte[] bytes() { return bytes.toByteArray(); }
    }

    private static final class RealtimeListener implements WebSocket.Listener {
        private final ArrayBlockingQueue<Inbound> queue;
        private final int maxChars;
        private final StringBuilder partial = new StringBuilder();
        private volatile boolean abandoned;
        private volatile WebSocket activeSocket;
        private RealtimeListener(ArrayBlockingQueue<Inbound> queue, int maxChars) { this.queue = queue; this.maxChars = maxChars; }
        @Override public void onOpen(WebSocket webSocket) {
            activeSocket = webSocket;
            if (abandoned) webSocket.abort(); else webSocket.request(1);
        }
        @Override public synchronized CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (partial.length() + data.length() > maxChars) {
                queue.offer(new Inbound(null, "spoken_preview_protocol_invalid", false));
                webSocket.abort();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            partial.append(data);
            if (last) {
                if (!queue.offer(new Inbound(partial.toString(), null, false))) {
                    queue.clear(); queue.offer(new Inbound(null, "spoken_preview_protocol_invalid", false)); webSocket.abort();
                }
                partial.setLength(0);
            }
            webSocket.request(1);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            queue.offer(new Inbound(null, null, true));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket webSocket, Throwable error) {
            queue.offer(new Inbound(null, "spoken_preview_provider_failed", false));
        }
        private void abandon() {
            abandoned = true;
            WebSocket socket = activeSocket;
            if (socket != null) socket.abort();
        }
    }

    private record Inbound(String text, String failureReason, boolean closed) { }

    @FunctionalInterface interface SocketConnector {
        java.util.concurrent.CompletableFuture<WebSocket> connect(URI uri, String apiKey, WebSocket.Listener listener,
                Duration timeout);
    }

    private static final class JdkSocketConnector implements SocketConnector {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        @Override public java.util.concurrent.CompletableFuture<WebSocket> connect(URI uri, String apiKey,
                WebSocket.Listener listener, Duration timeout) {
            return client.newWebSocketBuilder().connectTimeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .buildAsync(uri, listener);
        }
    }
}
