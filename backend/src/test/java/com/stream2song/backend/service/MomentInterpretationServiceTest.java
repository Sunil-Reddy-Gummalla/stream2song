package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.stream2song.backend.dto.MomentInterpretationResponse.EvidenceCitation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.stream2song.backend.dto.MemorableMomentsResponse;
import com.stream2song.backend.dto.MemorableMomentsResponse.Moment;
import com.stream2song.backend.dto.MemorableMomentsResponse.TranscriptEvidence;

class MomentInterpretationServiceTest {
    private MomentAnalysisSnapshotService snapshots;
    private AtomicReference<String> account;
    private MomentInterpretationService service;
    private StubProvider provider;

    @BeforeEach void setup() {
        snapshots = new MomentAnalysisSnapshotService(Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), java.time.ZoneOffset.UTC));
        account = new AtomicReference<>("owner");
        provider = new StubProvider();
        service = new MomentInterpretationService(account::get, snapshots, provider);
        long revision = snapshots.revision("owner", "clip");
        snapshots.store("owner", response(), revision);
    }

    @Test void returnsOnlyValidatedInterpretationAndCopiesTimeAndEvidenceFromSnapshot() {
        provider.result = List.of(validResult());
        var response = service.interpret("clip", "analysis");
        assertTrue(response.aiGenerated());
        assertTrue(response.unverifiedInterpretation());
        var moment = response.moments().getFirst();
        assertEquals(1.25, moment.startSeconds());
        assertEquals(3.5, moment.endSeconds());
        assertEquals("verified_target_session", moment.association());
        assertEquals("moment-1-t0", moment.evidence().getFirst().id());
        assertEquals("Wow!", moment.evidence().getFirst().excerpt());
        assertEquals(1, provider.calls);
    }

    @Test void rejectsForeignMissingDuplicateAndUncitedInterpretations() {
        provider.result = List.of(new MomentInterpretationProvider.Interpretation("foreign", "s", "t", "h", "n", List.of("moment-1-t0")));
        assertEquals("interpretation_output_invalid", assertThrows(MomentInterpretationService.InterpretationFailure.class,
                () -> service.interpret("clip", "analysis")).reason());
        provider.result = List.of(new MomentInterpretationProvider.Interpretation("moment-1", "s", "t", "h", "n", List.of("other-ref")));
        assertEquals("interpretation_output_invalid", assertThrows(MomentInterpretationService.InterpretationFailure.class,
                () -> service.interpret("clip", "analysis")).reason());
        provider.result = List.of();
        assertEquals("interpretation_output_invalid", assertThrows(MomentInterpretationService.InterpretationFailure.class,
                () -> service.interpret("clip", "analysis")).reason());
        account.set("other-owner");
        assertEquals("moment_analysis_expired", assertThrows(MomentInterpretationService.InterpretationFailure.class,
                () -> service.interpret("clip", "analysis")).reason());
    }

    @Test void rejectsSnapshotInvalidatedWhileProviderWasRunning() {
        provider.result = List.of(validResult());
        provider.duringCall = () -> snapshots.invalidate("owner", "clip");
        var failure = assertThrows(MomentInterpretationService.InterpretationFailure.class,
                () -> service.interpret("clip", "analysis"));
        assertEquals("moment_analysis_stale", failure.reason());
    }

    @Test void contextOnlyExtractionIsInterpretedAsUntimedBackgroundNotAClipMoment() {
        long revision = snapshots.revision("owner", "clip-context");
        var context = new MemorableMomentsResponse.SessionChatContext(3, 3, 2, 1,
                List.of(new MemorableMomentsResponse.SessionChatMessage("chat-1", "hello chat"),
                        new MemorableMomentsResponse.SessionChatMessage("chat-2", "nice stream")));
        var extraction = new MemorableMomentsResponse("analysis-context", "clip-context",
                new MemorableMomentsResponse.SourceClip("title", "creator", 20, null, null),
                "unknown", "unaligned", null, "not aligned", true, "0-100", "no clip cues", List.of(), context);
        snapshots.store("owner", extraction, revision);
        provider.expectClipEvidence = false;
        provider.result = List.of(new MomentInterpretationProvider.Interpretation(
                MomentAnalysisSnapshotService.SESSION_CHAT_CONTEXT_REFERENCE_KEY, "Recent chat feels upbeat.",
                "Friendly", "Positive", "Provides only general stream background.",
                List.of("chat-1", "chat-2"), "session_context"));

        var result = service.interpret("clip-context", "analysis-context");

        assertTrue(result.moments().isEmpty());
        assertEquals(1, result.sessionContexts().size());
        assertEquals("unknown", result.sessionContexts().getFirst().association());
        assertEquals("session_chat_context", result.sessionContexts().getFirst().evidence().getFirst().kind());
        assertNull(result.sessionContexts().getFirst().evidence().getFirst().startSeconds());
        assertNull(result.sessionContexts().getFirst().evidence().getFirst().endSeconds());
        assertEquals("session_context", provider.inputs.getLast().kind());
        assertNull(provider.inputs.getLast().startSeconds());
        assertNull(provider.inputs.getLast().endSeconds());
    }

    @Test void transcriptOnlyExtractionSendsEveryBoundedUtteranceAndReturnsServerTimedCitations() {
        long revision = snapshots.revision("owner", "clip-transcript");
        List<String> lines = List.of("bullshit application", "how can I", "checking", "break everything", "start", "not know world");
        List<MemorableMomentsResponse.TranscriptUtterance> utterances = java.util.stream.IntStream.range(0, lines.size())
                .mapToObj(i -> new MemorableMomentsResponse.TranscriptUtterance("clip-transcript-" + (i + 1), lines.get(i), (double) i, i + 0.5)).toList();
        var transcriptContext = new MemorableMomentsResponse.TranscriptContext(lines.size(), lines.size(), lines.size(),
                0, lines.stream().mapToInt(String::length).sum(), 0, "unknown", "utterances", utterances);
        var extraction = new MemorableMomentsResponse("analysis-transcript", "clip-transcript",
                new MemorableMomentsResponse.SourceClip("title", "creator", 20, null, null), "unknown", "unaligned", null,
                "clip timing unavailable", true, "0-100", "No scored moment", List.of(),
                MemorableMomentsResponse.SessionChatContext.empty(), transcriptContext);
        snapshots.store("owner", extraction, revision);
        provider.expectClipEvidence = false;
        provider.result = List.of(new MomentInterpretationProvider.Interpretation(
                MomentAnalysisSnapshotService.CLIP_TRANSCRIPT_CONTEXT_REFERENCE_KEY, "The speaker is working through a broken application.",
                "Frustrated", "Tense", "The remarks shift from checking the issue to a broader uncertainty.",
                utterances.stream().map(MemorableMomentsResponse.TranscriptUtterance::id).toList(), "clip_context"));

        var result = service.interpret("clip-transcript", "analysis-transcript");

        assertTrue(result.moments().isEmpty());
        assertEquals(1, result.transcriptContexts().size());
        assertEquals(lines, result.transcriptContexts().getFirst().evidence().stream().map(EvidenceCitation::excerpt).toList());
        assertEquals(0.0, result.transcriptContexts().getFirst().evidence().getFirst().startSeconds());
        assertEquals("clip_context", provider.inputs.getLast().kind());
        assertEquals(lines, provider.inputs.getLast().evidence().stream().map(MomentInterpretationProvider.Evidence::excerpt).toList());
    }

    private MomentInterpretationProvider.Interpretation validResult() {
        return new MomentInterpretationProvider.Interpretation("moment-1", "A brief excited reaction.",
                "Excited", "High excitement", "May be a memorable reaction.", List.of("moment-1-t0"));
    }

    private MemorableMomentsResponse response() {
        Moment moment = new Moment("moment-1", "excited speech", 1.25, 3.5, 40,
                List.of(new TranscriptEvidence("Wow!", 1.25, 3.5)), List.of(), "clip",
                "verified_target_session", "deterministic", List.of("transcript_cue"));
        return new MemorableMomentsResponse("analysis", "clip",
                new MemorableMomentsResponse.SourceClip("title", "creator", 20, "vod", 10),
                "verified_target_session", "unaligned", null, "timeline unaligned", true, "0–100", null, List.of(moment));
    }

    private static final class StubProvider implements MomentInterpretationProvider {
        private List<Interpretation> result;
        private int calls;
        private boolean expectClipEvidence = true;
        private List<InputMoment> inputs = List.of();
        private Runnable duringCall = () -> { };
        @Override public List<Interpretation> interpret(List<InputMoment> moments, String context) {
            calls++;
            inputs = List.copyOf(moments);
            if (expectClipEvidence) {
                assertEquals("moment-1", moments.getFirst().momentId());
                assertTrue(moments.getFirst().evidence().getFirst().excerpt().contains("Wow"));
            }
            duringCall.run();
            return result;
        }
    }
}
