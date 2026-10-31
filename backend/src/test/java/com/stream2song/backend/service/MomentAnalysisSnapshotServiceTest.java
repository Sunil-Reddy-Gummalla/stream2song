package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.stream2song.backend.dto.MemorableMomentsResponse;
import com.stream2song.backend.dto.MemorableMomentsResponse.Moment;
import com.stream2song.backend.dto.MemorableMomentsResponse.TranscriptEvidence;
import com.stream2song.backend.dto.MomentInterpretationResponse;
import com.stream2song.backend.dto.ClipLyricsResponse;

class MomentAnalysisSnapshotServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    @Test
    void snapshotsExpireAreAccountScopedAndAreInvalidatedForRefresh() {
        MutableClock clock = new MutableClock(NOW);
        MomentAnalysisSnapshotService service = new MomentAnalysisSnapshotService(clock);
        long revision = service.revision("owner", "clip");
        MemorableMomentsResponse response = response("analysis-1", "clip");
        service.store("owner", response, revision);
        assertNotNull(service.get("owner", "clip", "analysis-1"));
        assertNull(service.get("other", "clip", "analysis-1"));
        assertEquals(1, service.get("owner", "clip", "analysis-1").references().get("moment-1").size());

        service.invalidate("owner", "clip");
        assertNull(service.get("owner", "clip", "analysis-1"));
        assertThrows(MomentAnalysisSnapshotService.SnapshotFailure.class,
                () -> service.store("owner", response("analysis-old", "clip"), revision));

        long current = service.revision("owner", "clip");
        service.store("owner", response("analysis-2", "clip"), current);
        clock.advance(Duration.ofMinutes(31));
        assertNull(service.get("owner", "clip", "analysis-2"));
    }

    @Test
    void revisionEvictionCannotRecreateAnOldGenerationToken() {
        MomentAnalysisSnapshotService service = new MomentAnalysisSnapshotService(new MutableClock(NOW));
        long old = service.revision("owner", "original");
        for (int i = 0; i < 130; i++) service.revision("owner", "clip-" + i);
        service.revision("owner", "original");
        assertThrows(MomentAnalysisSnapshotService.SnapshotFailure.class,
                () -> service.store("owner", response("late-analysis", "original"), old));
    }

    @Test
    void boundedSnapshotCapacityEvictsOldestAnalysis() {
        MomentAnalysisSnapshotService service = new MomentAnalysisSnapshotService(new MutableClock(NOW));
        for (int i = 0; i < 101; i++) {
            String clip = "clip-" + i;
            service.store("owner", response("analysis-" + i, clip), service.revision("owner", clip));
        }
        assertNull(service.get("owner", "clip-0", "analysis-0"));
        assertNotNull(service.get("owner", "clip-100", "analysis-100"));
    }

    @Test void storesTypedSessionChatContextReferencesWithNoTimingAndCopiedCounts() {
        MomentAnalysisSnapshotService service = new MomentAnalysisSnapshotService(new MutableClock(NOW));
        var context = new MemorableMomentsResponse.SessionChatContext(8, 8, 2, 6,
                List.of(new MemorableMomentsResponse.SessionChatMessage("chat-1", "ordinary chat"),
                        new MemorableMomentsResponse.SessionChatMessage("chat-2", "another line")));
        var response = new MemorableMomentsResponse("analysis-context", "clip-context",
                new MemorableMomentsResponse.SourceClip("title", "creator", 10, null, null),
                "unknown", "unaligned", null, "not aligned", true, "0-100", null, List.of(), context);
        service.store("owner", response, service.revision("owner", "clip-context"));

        var snapshot = service.get("owner", "clip-context", "analysis-context");
        var references = snapshot.references().get(MomentAnalysisSnapshotService.SESSION_CHAT_CONTEXT_REFERENCE_KEY);
        assertEquals(2, references.size());
        assertEquals("session_chat_context", references.getFirst().kind());
        assertNull(references.getFirst().startSeconds());
        assertNull(references.getFirst().endSeconds());
        assertEquals(8, snapshot.response().sessionChatContext().availableCount());
        assertEquals(List.of("ordinary chat", "another line"), snapshot.response().sessionChatContext().messages().stream()
                .map(MemorableMomentsResponse.SessionChatMessage::text).toList());
    }

    @Test void storesFullBoundedClipTranscriptContextWithUtteranceTimes() {
        MomentAnalysisSnapshotService service = new MomentAnalysisSnapshotService(new MutableClock(NOW));
        var transcript = new MemorableMomentsResponse.TranscriptContext(2, 2, 2, 0, 24, 0, "unknown", "utterances", List.of(
                new MemorableMomentsResponse.TranscriptUtterance("clip-transcript-1", "bullshit application", 1.0, 1.5),
                new MemorableMomentsResponse.TranscriptUtterance("clip-transcript-2", "how can I", 2.0, 2.5)));
        var response = new MemorableMomentsResponse("analysis-transcript", "clip-transcript",
                new MemorableMomentsResponse.SourceClip("title", "creator", 10, null, null), "unknown", "unaligned", null,
                "timing context", true, "0-100", null, List.of(), MemorableMomentsResponse.SessionChatContext.empty(), transcript);
        service.store("owner", response, service.revision("owner", "clip-transcript"));
        var snapshot = service.get("owner", "clip-transcript", "analysis-transcript");
        var refs = snapshot.references().get(MomentAnalysisSnapshotService.CLIP_TRANSCRIPT_CONTEXT_REFERENCE_KEY);
        assertEquals(List.of("bullshit application", "how can I"), refs.stream().map(MomentAnalysisSnapshotService.EvidenceReference::excerpt).toList());
        assertEquals(1.0, refs.getFirst().startSeconds());
        assertEquals("how can I", snapshot.response().transcriptContext().utterances().getLast().text());
    }

    @Test void keepsOnlyTheLatestExtractionSnapshotForOneClip() {
        MomentAnalysisSnapshotService service = new MomentAnalysisSnapshotService(new MutableClock(NOW));
        long revision = service.revision("owner", "clip");
        service.store("owner", response("analysis-1", "clip"), revision);
        service.store("owner", response("analysis-2", "clip"), revision);
        assertNull(service.get("owner", "clip", "analysis-1"));
        assertNotNull(service.get("owner", "clip", "analysis-2"));
    }

    @Test void interpretationSnapshotIsReplacedByAnalysisAndInvalidatedWithSource() {
        MomentAnalysisSnapshotService service = new MomentAnalysisSnapshotService(new MutableClock(NOW));
        var scope = new TwitchBroadcasterProvider.ConnectionScope("owner", 22L);
        long rev=service.revision(scope,"clip"); service.store(scope,response("analysis-1","clip"),rev);
        var first=new MomentInterpretationResponse("interpretation-1","analysis-1","clip",true,true,List.of());
        service.storeInterpretation(scope,"clip","analysis-1",first);
        assertNotNull(service.getInterpretation(scope,"clip","analysis-1","interpretation-1"));
        var replacement=new MomentInterpretationResponse("interpretation-2","analysis-1","clip",true,true,List.of());
        service.storeInterpretation(scope,"clip","analysis-1",replacement);
        assertNull(service.getInterpretation(scope,"clip","analysis-1","interpretation-1"));
        assertNotNull(service.getInterpretation(scope,"clip","analysis-1","interpretation-2"));
        assertNull(service.getInterpretation(new TwitchBroadcasterProvider.ConnectionScope("owner",23L),"clip","analysis-1","interpretation-2"));
        service.invalidate("owner","clip");
        assertNull(service.getInterpretation(scope,"clip","analysis-1","interpretation-2"));
    }

    @Test void lyricsSnapshotsAreAccountScopedExpireWithInterpretationAndAreInvalidatedOnReplacement() {
        MutableClock clock=new MutableClock(NOW);MomentAnalysisSnapshotService service=new MomentAnalysisSnapshotService(clock);
        var scope=new TwitchBroadcasterProvider.ConnectionScope("owner",32L);long revision=service.revision(scope,"clip");service.store(scope,response("analysis-1","clip"),revision);
        var citation=new MomentInterpretationResponse.EvidenceCitation("moment-1-t0","transcript","Wow",1.0,2.0);
        var interpreted=new MomentInterpretationResponse("interpretation-1","analysis-1","clip",true,true,
                List.of(new MomentInterpretationResponse.InterpretedMoment("moment-1","summary","tone","excited","significance",1.0,2.0,"unknown",List.of(citation))));
        service.storeInterpretation(scope,"clip","analysis-1",interpreted);
        var source=new ClipLyricsResponse.SourceMoment("moment-1",1.0,2.0,"unknown",List.of(citation));
        var lyrics=new ClipLyricsResponse("lyrics-1","analysis-1","interpretation-1","clip","pop","playful","medium",true,true,
                "unknown",null,"unknown association","Song",List.of(new ClipLyricsResponse.Section("verse","Verse",List.of("one","two"),List.of(source))));
        var interpretationExpiry=service.getInterpretation(scope,"clip","analysis-1","interpretation-1").expiresAt();
        service.storeLyrics(scope,lyrics,interpretationExpiry);
        assertNotNull(service.getLyrics(scope,"clip","lyrics-1"));
        assertNull(service.getLyrics(new TwitchBroadcasterProvider.ConnectionScope("owner",33L),"clip","lyrics-1"));
        var replacement=new MomentInterpretationResponse("interpretation-2","analysis-1","clip",true,true,List.of());
        service.storeInterpretation(scope,"clip","analysis-1",replacement);
        assertNull(service.getLyrics(scope,"clip","lyrics-1"));
        service.storeInterpretation(scope,"clip","analysis-1",interpreted);
        service.storeLyrics(scope,lyrics,service.getInterpretation(scope,"clip","analysis-1","interpretation-1").expiresAt());
        clock.advance(Duration.ofMinutes(31));assertNull(service.getLyrics(scope,"clip","lyrics-1"));
    }

    private MemorableMomentsResponse response(String analysisId, String clipId) {
        Moment moment = new Moment("moment-1", "Wow", 1.0, 2.0, 40,
                List.of(new TranscriptEvidence("Wow", 1.0, 2.0)), List.of(), clipId,
                "unknown", "deterministic", List.of("transcript_cue"));
        return new MemorableMomentsResponse(analysisId, clipId,
                new MemorableMomentsResponse.SourceClip("title", "creator", 10, null, null),
                "unknown", "unaligned", null, "context", true, "0-100", null, List.of(moment));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
