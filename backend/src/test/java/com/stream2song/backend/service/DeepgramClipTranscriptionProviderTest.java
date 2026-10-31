package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import com.stream2song.backend.client.JavaHttpDeepgramApiTransport;
import com.stream2song.backend.config.DeepgramProperties;

class DeepgramClipTranscriptionProviderTest {
    private static final String API_KEY = "test-deepgram-key";

    @Test
    void buildsNova3UtteranceRequestAndParsesProviderIndependentTimedSegments() {
        RecordingTransport transport = new RecordingTransport(new DeepgramApiTransport.Response(200, """
                {"results":{"channels":[{"alternatives":[{"transcript":"Hello there.","words":[]}]}],
                "utterances":[{"start":0.25,"end":0.9,"transcript":"Hello"},
                {"start":1.1,"end":1.8,"transcript":"there."}]}}
                """.getBytes(StandardCharsets.UTF_8)), null);
        var provider = new DeepgramClipTranscriptionProvider(new DeepgramProperties(API_KEY), JsonMapper.builder().build(), transport);

        var transcript = provider.transcribe(new byte[] {1, 2, 3}, "video/mp4");

        HttpRequest sent = transport.request;
        assertEquals("https://api.deepgram.com/v1/listen?model=nova-3&utterances=true&smart_format=true",
                sent.uri().toString());
        assertEquals("Token " + API_KEY, sent.headers().firstValue("Authorization").orElseThrow());
        assertEquals("video/mp4", sent.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(sent.timeout().orElseThrow().toSeconds() <= 45);
        assertEquals("Hello there.", transcript.text());
        assertEquals(2, transcript.segments().size());
        assertEquals(new ClipTranscriptionProvider.Segment(0.25, 0.9, "Hello"), transcript.segments().getFirst());
        assertEquals(new ClipTranscriptionProvider.Segment(1.1, 1.8, "there."), transcript.segments().get(1));
        assertFalse(transcript.toString().contains(API_KEY));
    }

    @Test
    void mapsProviderHttpFailureToSafeError() {
        DeepgramApiTransport transport = new RecordingTransport(new DeepgramApiTransport.Response(500,
                "provider secret details".getBytes(StandardCharsets.UTF_8)), null);
        var provider = new DeepgramClipTranscriptionProvider(new DeepgramProperties(API_KEY), JsonMapper.builder().build(), transport);
        var failure = assertThrows(DeepgramClipTranscriptionProvider.TranscriptionProviderFailure.class,
                () -> provider.transcribe(new byte[] {1}, "audio/mpeg"));
        assertEquals("transcription_provider_failed", failure.reason());
        assertEquals(502, failure.status());
        assertFalse(failure.toString().contains("provider secret details"));
        assertFalse(failure.toString().contains(API_KEY));
    }

    @Test
    void mapsTransportTimeoutWithoutExposingDetails() {
        DeepgramApiTransport transport = new RecordingTransport(null,
                new JavaHttpDeepgramApiTransport.DeepgramTransportFailure(true));
        var provider = new DeepgramClipTranscriptionProvider(new DeepgramProperties(API_KEY), JsonMapper.builder().build(), transport);
        var failure = assertThrows(DeepgramClipTranscriptionProvider.TranscriptionProviderFailure.class,
                () -> provider.transcribe(new byte[] {1}, "audio/mpeg"));
        assertEquals("transcription_provider_timeout", failure.reason());
        assertEquals(504, failure.status());
    }

    private static final class RecordingTransport implements DeepgramApiTransport {
        private final Response response;
        private final RuntimeException failure;
        private HttpRequest request;
        private RecordingTransport(Response response, RuntimeException failure) {
            this.response = response;
            this.failure = failure;
        }
        @Override public Response send(HttpRequest request) {
            this.request = request;
            if (failure != null) throw failure;
            return response;
        }
    }
}
