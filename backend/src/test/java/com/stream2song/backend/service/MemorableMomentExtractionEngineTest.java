package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.stream2song.backend.dto.ClipTranscriptionResponse.TimedTranscriptSegment;
import com.stream2song.backend.dto.MemorableMomentsResponse.Moment;
import com.stream2song.backend.dto.TwitchChatHighlightsResponse.Highlight;

class MemorableMomentExtractionEngineTest {
    private final MemorableMomentExtractionEngine engine = new MemorableMomentExtractionEngine();
    private final Instant streamStart = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    void emitsTranscriptOnlyCueWithClipRelativeTime() {
        var moments = engine.extract("clip", 30, "verified_target_session", "Wow, that was amazing!",
                List.of(new TimedTranscriptSegment(2.0, 4.0, "Wow, that was amazing!")), List.of(),
                null, 90, true);
        assertEquals(1, moments.size());
        assertEquals(2.0, moments.getFirst().startSeconds());
        assertEquals(4.0, moments.getFirst().endSeconds());
        assertTrue(moments.getFirst().contributors().contains("transcript_cue"));
        assertTrue(moments.getFirst().score() <= 100);
    }

    @Test
    void emitsUntimedTranscriptCueWithoutInventingAClipRange() {
        var moments = engine.extract("clip", 30, "unknown", "Wow, that was amazing!", List.of(), List.of(),
                null, null, false);
        assertEquals(1, moments.size());
        assertNull(moments.getFirst().startSeconds());
        assertNull(moments.getFirst().endSeconds());
        assertNull(moments.getFirst().transcriptEvidence().getFirst().startSeconds());
    }

    @Test
    void invalidEndMakesEntireTranscriptRangeUnavailableAndLetsGoIsRecognized() {
        var moments = engine.extract("clip", 30, "unknown", "let's go!",
                List.of(new TimedTranscriptSegment(2.0, 31.0, "let's go!")), List.of(), null, null, false);
        assertEquals(1, moments.size());
        assertNull(moments.getFirst().startSeconds());
        assertNull(moments.getFirst().endSeconds());
        assertNull(moments.getFirst().transcriptEvidence().getFirst().endSeconds());
    }

    @Test
    void emitsAlignedChatOnlyWindowWithUtcAndClipRelativeEvidence() {
        List<Highlight> chat = List.of(highlight(90, 100, 82, "Repeated chat reactions: pog, wow.",
                List.of("pog", "wow", "let's go")));
        var moments = engine.extract("clip", 30, "verified_target_session", "", List.of(), chat,
                streamStart, 90, true);
        assertEquals(1, moments.size());
        assertTrue(moments.getFirst().transcriptEvidence().isEmpty());
        assertEquals(0.0, moments.getFirst().startSeconds());
        assertEquals(10.0, moments.getFirst().endSeconds());
        assertEquals(streamStart.plusSeconds(90), moments.getFirst().chatEvidence().getFirst().windowStartUtc());
        assertEquals(3, moments.getFirst().chatEvidence().getFirst().messageCount());
        assertEquals(82, moments.getFirst().chatEvidence().getFirst().score());
        assertTrue(moments.getFirst().chatEvidence().getFirst().explanation().contains("burst"));
    }

    @Test
    void combinesChatAndTranscriptWhenRelativeWindowsOverlap() {
        List<Highlight> chat = List.of(highlight(90, 100, 82, "Repeated chat reactions: pog, wow.",
                List.of("pog", "wow", "let's go")));
        var moments = engine.extract("clip", 30, "verified_target_session", "Wow!",
                List.of(new TimedTranscriptSegment(1.0, 5.0, "Wow!")), chat,
                streamStart, 90, true);
        assertEquals(1, moments.size());
        assertEquals(1, moments.getFirst().transcriptEvidence().size());
        assertEquals(1, moments.getFirst().chatEvidence().size());
        assertTrue(moments.getFirst().contributors().contains("chat_activity"));
        assertTrue(moments.getFirst().contributors().contains("transcript_cue"));
    }

    @Test
    void mergesOverlappingTranscriptCandidatesWithoutDuplicateScoreOrEvidenceLoss() {
        var moments = engine.extract("clip", 30, "verified_target_session", "Wow! Incredible!",
                List.of(new TimedTranscriptSegment(1.0, 3.0, "Wow!"),
                        new TimedTranscriptSegment(2.5, 4.0, "Incredible!")), List.of(), null, 0, false);
        assertEquals(1, moments.size());
        Moment moment = moments.getFirst();
        assertEquals(2, moment.transcriptEvidence().size());
        assertEquals(40, moment.score());
        assertEquals(1.0, moment.startSeconds());
        assertEquals(4.0, moment.endSeconds());
    }

    @Test
    void separateRepeatedPhraseOccurrencesKeepTheirOwnRangesAndStableIds() {
        var moments = engine.extract("clip", 30, "verified_target_session", "Pog! Pog!",
                List.of(new TimedTranscriptSegment(1.0, 2.0, "Pog!"),
                        new TimedTranscriptSegment(8.0, 9.0, "Pog!")), List.of(), null, 0, false);
        var again = engine.extract("clip", 30, "verified_target_session", "Pog! Pog!",
                List.of(new TimedTranscriptSegment(1.0, 2.0, "Pog!"),
                        new TimedTranscriptSegment(8.0, 9.0, "Pog!")), List.of(), null, 0, false);
        assertEquals(2, moments.size());
        assertEquals(1.0, moments.get(0).startSeconds());
        assertEquals(2.0, moments.get(0).endSeconds());
        assertEquals(8.0, moments.get(1).startSeconds());
        assertEquals(9.0, moments.get(1).endSeconds());
        assertTrue(moments.stream().allMatch(moment -> moment.contributors().contains("repeated_phrase")));
        assertTrue(moments.stream().allMatch(moment -> moment.score() == 60));
        assertEquals(moments.stream().map(Moment::id).toList(), again.stream().map(Moment::id).toList());
    }

    @Test
    void duplicateNormalizedSegmentsAtSameOrUntimedLocationDoNotInflateScoresOrEvidence() {
        var duplicateTimed = engine.extract("clip", 30, "unknown", "Wow! Wow!",
                List.of(new TimedTranscriptSegment(1.0, 2.0, "Wow!"),
                        new TimedTranscriptSegment(1.0, 2.0, " WOW ")), List.of(), null, null, false);
        assertEquals(1, duplicateTimed.size());
        assertEquals(40, duplicateTimed.getFirst().score());
        assertEquals(1, duplicateTimed.getFirst().transcriptEvidence().size());
        assertFalse(duplicateTimed.getFirst().contributors().contains("repeated_phrase"));

        var duplicateUntimed = engine.extract("clip", 30, "unknown", "Wow! Wow!",
                List.of(new TimedTranscriptSegment(null, null, "Wow!"),
                        new TimedTranscriptSegment(null, null, "wow")), List.of(), null, null, false);
        assertEquals(1, duplicateUntimed.size());
        assertEquals(40, duplicateUntimed.getFirst().score());
        assertEquals(1, duplicateUntimed.getFirst().transcriptEvidence().size());
    }

    @Test void repeatedFunctionOnlyTranscriptDoesNotBecomeAMemorableCue() {
        var moments = engine.extract("clip", 30, "unknown", "Let's. Let's.",
                List.of(new TimedTranscriptSegment(1.0, 1.5, "Let's."),
                        new TimedTranscriptSegment(5.0, 5.5, "Let's.")), List.of(), null, null, false);
        assertTrue(moments.isEmpty());
    }

    @Test
    void overlappingDuplicatePhraseSpansDoNotCountAsIndependentRepetition() {
        var moments = engine.extract("clip", 30, "unknown", "Pog! Pog!",
                List.of(new TimedTranscriptSegment(1.0, 3.0, "Pog!"),
                        new TimedTranscriptSegment(2.0, 4.0, "pog")), List.of(), null, null, false);
        assertEquals(1, moments.size());
        assertEquals(40, moments.getFirst().score());
        assertFalse(moments.getFirst().contributors().contains("repeated_phrase"));
        assertEquals(2, moments.getFirst().transcriptEvidence().size());
        assertEquals(1.0, moments.getFirst().startSeconds());
        assertEquals(4.0, moments.getFirst().endSeconds());
    }

    @Test
    void exactAdjacentChatHighlightsRemainSeparateMoments() {
        List<Highlight> chat = List.of(
                highlight(90, 100, 80, "First burst", List.of("pog")),
                highlight(100, 110, 80, "Second burst", List.of("wow")));
        var moments = engine.extract("clip", 30, "verified_target_session", "", List.of(), chat,
                streamStart, 90, false);
        assertEquals(2, moments.size());
        assertEquals(0.0, moments.get(0).startSeconds());
        assertEquals(10.0, moments.get(0).endSeconds());
        assertEquals(10.0, moments.get(1).startSeconds());
        assertEquals(20.0, moments.get(1).endSeconds());
        assertTrue(moments.stream().allMatch(moment -> moment.chatEvidence().size() == 1));
    }

    @Test
    void noTranscriptOrAlignedEvidenceReturnsNoCandidates() {
        assertTrue(engine.extract("clip", 30, "unknown", "", List.of(), List.of(), null, null, false).isEmpty());
    }

    @Test
    void outputAndStableIdsAreDeterministic() {
        List<TimedTranscriptSegment> transcript = List.of(new TimedTranscriptSegment(1.0, 2.0, "Wow!"));
        List<Moment> first = engine.extract("clip", 30, "unknown", "Wow!", transcript, List.of(), null, null, false);
        List<Moment> second = engine.extract("clip", 30, "unknown", "Wow!", transcript, List.of(), null, null, false);
        assertEquals(first, second);
    }

    private Highlight highlight(long startSeconds, long endSeconds, double score, String summary, List<String> messages) {
        return new Highlight(streamStart.plusSeconds(startSeconds), streamStart.plusSeconds(endSeconds), score,
                messages.size(), summary, "3 messages with activity burst and repeated reaction terms.", messages);
    }
}
