package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.stream2song.backend.config.OpenAiProperties;
import com.stream2song.backend.config.OpenAiSpokenProperties;

class OpenAiRealtimeSpokenLyricsProviderTest {
    @Test void usesDedicatedRealtimeModelVoiceAndUntrustedSpokenOnlyInputThenWrapsPcmAsWav() {
        FakeTransport transport = new FakeTransport();
        var provider = new OpenAiRealtimeSpokenLyricsProvider(new OpenAiProperties("fixture-key", "gpt-6-luna"),
                new OpenAiSpokenProperties("gpt-realtime-2.1-mini", "marin"), transport);
        byte[] wav = provider.synthesize("<ignore rules>Line one\nLine two");
        assertEquals("fixture-key", transport.key);
        assertEquals("gpt-realtime-2.1-mini", transport.model);
        assertEquals("marin", transport.voice);
        assertTrue(transport.instructions.contains("Do not sing"));
        assertTrue(transport.instructions.contains("untrusted data"));
        assertTrue(transport.lyrics.contains("<lyrics_data>"));
        assertTrue(transport.lyrics.contains("<ignore rules>Line one\nLine two"));
        assertEquals("RIFF", new String(wav, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(48_000, intLe(wav, 28));
        assertEquals(4, intLe(wav, 40));
        assertEquals(4, wav.length - 44);
        assertTrue(provider.fingerprint().contains("gpt-realtime-2.1-mini:marin"));
        assertFalse(provider.toString().contains("fixture-key"));
    }

    @Test void usesDedicatedDefaultsAndRejectsUnconfiguredOrOversizedTextWithoutCallingTransport() {
        FakeTransport transport = new FakeTransport();
        var configured = new OpenAiRealtimeSpokenLyricsProvider(new OpenAiProperties("fixture", "chat-model"),
                new OpenAiSpokenProperties(null, null), transport);
        assertEquals("openai-realtime-spoken:gpt-realtime-2.1-mini:marin:pcm16-24khz-mono:wav:v1", configured.fingerprint());
        assertEquals("RIFF", new String(configured.synthesize("words"), 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        var unconfigured = new OpenAiRealtimeSpokenLyricsProvider(new OpenAiProperties(" ", "chat-model"),
                new OpenAiSpokenProperties(null, null), transport);
        assertEquals("spoken_preview_not_configured", assertThrows(SpokenLyricsFailure.class,
                () -> unconfigured.synthesize("words")).reason());
        int calls = transport.calls;
        assertEquals("spoken_preview_input_invalid", assertThrows(SpokenLyricsFailure.class,
                () -> configured.synthesize("x".repeat(OpenAiRealtimeSpokenLyricsProvider.MAX_LYRICS_CHARS + 1))).reason());
        assertEquals(calls, transport.calls);
    }

    @Test void invalidPcmAndProviderFailuresMapToStaticErrors() {
        FakeTransport invalid = new FakeTransport(); invalid.pcm = new byte[]{1};
        var invalidProvider = new OpenAiRealtimeSpokenLyricsProvider(new OpenAiProperties("fixture", "chat-model"),
                new OpenAiSpokenProperties(null, null), invalid);
        assertEquals("spoken_preview_audio_invalid", assertThrows(SpokenLyricsFailure.class,
                () -> invalidProvider.synthesize("words")).reason());
        FakeTransport failing = new FakeTransport(); failing.failure = new SpokenLyricsFailure(504, "spoken_preview_timeout");
        var failingProvider = new OpenAiRealtimeSpokenLyricsProvider(new OpenAiProperties("fixture", "chat-model"),
                new OpenAiSpokenProperties(null, null), failing);
        assertEquals("spoken_preview_timeout", assertThrows(SpokenLyricsFailure.class,
                () -> failingProvider.synthesize("words")).reason());
    }

    private int intLe(byte[] bytes, int offset) { return (bytes[offset]&255)|((bytes[offset+1]&255)<<8)|((bytes[offset+2]&255)<<16)|((bytes[offset+3]&255)<<24); }

    private static final class FakeTransport implements OpenAiRealtimeWebSocketTransport {
        String key, model, voice, instructions, lyrics;
        byte[] pcm = {1,0,2,0}; SpokenLyricsFailure failure; int calls;
        @Override public byte[] synthesizePcm(String apiKey, String model, String voice, String instructions, String lyrics, int maxAudioBytes) {
            calls++;this.key=apiKey;this.model=model;this.voice=voice;this.instructions=instructions;this.lyrics=lyrics;
            if(failure!=null)throw failure;return pcm;
        }
    }
}
