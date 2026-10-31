package com.stream2song.backend.service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.stream2song.backend.config.DeepgramProperties;
import com.stream2song.backend.client.JavaHttpDeepgramApiTransport;

@Component
public class DeepgramClipTranscriptionProvider implements ClipTranscriptionProvider {
    private static final String MODEL_OPTIONS_VERSION = "deepgram:nova-3:utterances=true:smart_format=true:v1";
    private static final URI LISTEN_URI = URI.create("https://api.deepgram.com/v1/listen?model=nova-3&utterances=true&smart_format=true");
    private final DeepgramProperties properties;
    private final ObjectMapper objectMapper;
    private final DeepgramApiTransport transport;

    public DeepgramClipTranscriptionProvider(DeepgramProperties properties, ObjectMapper objectMapper,
            DeepgramApiTransport transport) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transport = transport;
    }

    @Override public boolean isConfigured() { return properties.isConfigured(); }
    @Override public String cacheFingerprint() { return MODEL_OPTIONS_VERSION; }

    @Override
    public Transcript transcribe(byte[] media, String contentType) {
        if (!properties.isConfigured()) throw new TranscriptionProviderFailure("transcription_provider_not_configured", 503);
        try {
            HttpRequest request = HttpRequest.newBuilder(LISTEN_URI).timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Token " + properties.apiKey())
                    .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(media)).build();
            DeepgramApiTransport.Response response = transport.send(request);
            if (response.statusCode() == 429) throw new TranscriptionProviderFailure("transcription_provider_rate_limited", 503);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new TranscriptionProviderFailure("transcription_provider_failed", 502);
            }
            JsonNode root = objectMapper.readTree(response.body());
            String text = root.path("results").path("channels").path(0).path("alternatives").path(0)
                    .path("transcript").asText("");
            List<Segment> segments = new ArrayList<>();
            for (JsonNode utterance : root.path("results").path("utterances")) {
                String segmentText = utterance.path("transcript").asText("");
                if (!segmentText.isBlank() && utterance.path("start").isNumber() && utterance.path("end").isNumber()) {
                    segments.add(new Segment(utterance.path("start").asDouble(), utterance.path("end").asDouble(), segmentText));
                }
            }
            return new Transcript(text, List.copyOf(segments));
        } catch (TranscriptionProviderFailure failure) {
            throw failure;
        } catch (JavaHttpDeepgramApiTransport.DeepgramTransportFailure failure) {
            if (failure.timeout()) throw new TranscriptionProviderFailure("transcription_provider_timeout", 504);
            throw new TranscriptionProviderFailure("transcription_provider_failed", 502);
        } catch (RuntimeException exception) {
            if (exception instanceof TranscriptionProviderFailure failure) throw failure;
            throw new TranscriptionProviderFailure("transcription_provider_failed", 502);
        }
    }

    public static class TranscriptionProviderFailure extends RuntimeException {
        private final String reason;
        private final int status;
        public TranscriptionProviderFailure(String reason, int status) { super(reason); this.reason = reason; this.status = status; }
        public String reason() { return reason; }
        public int status() { return status; }
    }
}
