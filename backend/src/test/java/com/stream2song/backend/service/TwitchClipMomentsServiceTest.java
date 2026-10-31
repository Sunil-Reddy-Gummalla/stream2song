package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.client.RestTemplate;
import com.stream2song.backend.config.TwitchOAuthProperties;
import com.stream2song.backend.service.TwitchAppAccessTokenService;
import com.stream2song.backend.client.TwitchClipsApiClient;
import com.stream2song.backend.client.TwitchClipsApiResponse;
import com.stream2song.backend.client.TwitchVideosApiClient;
import com.stream2song.backend.client.TwitchVideosApiResponse;
import com.stream2song.backend.dto.ClipTranscriptionResponse;
import com.stream2song.backend.dto.MemorableMomentsResponse;

class TwitchClipMomentsServiceTest {
    private static final String OWNER = "broadcaster-1";
    private static final String STREAM = "stream-1";
    private static final String VOD = "vod-1";
    private static final Instant START = Instant.parse("2026-10-29T10:00:00Z");
    private StubClips clips;
    private StubVideos videos;
    private TwitchStreamSessionService sessions;
    private TwitchChatMessageService messages;
    private StubTranscription transcription;
    private TwitchClipMomentsService service;

    @BeforeEach
    void setUp() {
        TwitchOAuthProperties properties = new TwitchOAuthProperties("client", "secret", "callback", "frontend");
        RestTemplate rest = new RestTemplate();
        clips = new StubClips(properties, rest);
        videos = new StubVideos(properties, rest);
        sessions = new TwitchStreamSessionService();
        messages = new TwitchChatMessageService();
        transcription = new StubTranscription();
        service = new TwitchClipMomentsService(() -> OWNER, clips, videos, sessions, messages,
                new TwitchChatHighlightService(() -> OWNER, sessions, messages), transcription,
                new MemorableMomentExtractionEngine());
        clips.response = new TwitchClipsApiResponse(List.of(clip(90, 30)), null);
        transcription.response = new ClipTranscriptionResponse("clip-1", "Wow, incredible!",
                List.of(new ClipTranscriptionResponse.TimedTranscriptSegment(2.0, 4.0, "Wow, incredible!")));
        videos.response = video(VOD, OWNER, STREAM, "archive", START);
    }

    @Test
    void alignsOnlyAfterAllGuardsAndRetainsTwitchStartWhenStreamIsOffline() throws Exception {
        sessions.recordOnline(OWNER, STREAM, START);
        sessions.recordOffline(OWNER, START.plusSeconds(1800));
        addChat("pog", "wow", "lets go");
        addChatAt(60, "pog", "wow", "pog", "wow", "pog");

        var result = service.extract("clip-1");

        assertEquals("verified_target_session", result.association());
        assertEquals("aligned_coarse_context", result.chatCorrelation());
        assertEquals("eventsub_delivery_timestamp", result.chatTimingBasis());
        assertTrue(result.chatCorrelationExplanation().contains("delivery timestamps"));
        assertTrue(result.chatCorrelationExplanation().contains("may lag speech"));
        var chatEvidence = result.moments().stream().flatMap(moment -> moment.chatEvidence().stream()).findFirst();
        assertTrue(chatEvidence.isPresent());
        assertEquals(3, chatEvidence.get().messageCount(), "only messages delivered within the clip range are scored");
        assertEquals(START, sessions.currentFor(OWNER).streamStartedAt());
    }

    @Test
    void timestampMismatchKeepsTranscriptMomentsButNeverCorrelatesChat() throws Exception {
        sessions.recordOnline(OWNER, STREAM, START);
        addChat("pog", "wow", "lets go");
        videos.response = video(VOD, OWNER, STREAM, "archive", START.plusSeconds(1));

        var result = service.extract("clip-1");

        assertEquals("verified_target_session", result.association());
        assertEquals("unaligned", result.chatCorrelation());
        assertNull(result.chatTimingBasis());
        assertFalse(result.moments().isEmpty());
        assertTrue(result.moments().stream().allMatch(moment -> moment.chatEvidence().isEmpty()));
    }

    @Test
    void nonArchiveVodDoesNotCorrelateChat() throws Exception {
        sessions.recordOnline(OWNER, STREAM, START);
        addChat("pog", "wow", "lets go");
        videos.response = video(VOD, OWNER, STREAM, "upload", START);
        var result = service.extract("clip-1");
        assertEquals("verified_target_session", result.association());
        assertEquals("unaligned", result.chatCorrelation());
        assertTrue(result.moments().stream().allMatch(moment -> moment.chatEvidence().isEmpty()));
    }

    @Test
    void wrongVodOwnerProducesUnknownAssociationAndNoChatEvidence() throws Exception {
        sessions.recordOnline(OWNER, STREAM, START);
        addChat("pog", "wow", "lets go");
        videos.response = video(VOD, "other-owner", STREAM, "archive", START);
        var result = service.extract("clip-1");
        assertEquals("unknown", result.association());
        assertEquals("unaligned", result.chatCorrelation());
        assertTrue(result.moments().stream().allMatch(moment -> moment.chatEvidence().isEmpty()));
    }

    @Test
    void wrongVodStreamReportsDifferentSessionWithoutChatCorrelation() throws Exception {
        sessions.recordOnline(OWNER, STREAM, START);
        addChat("pog", "wow", "lets go");
        videos.response = video(VOD, OWNER, "other-stream", "archive", START);
        var result = service.extract("clip-1");
        assertEquals("different_session", result.association());
        assertEquals("unaligned", result.chatCorrelation());
        assertTrue(result.moments().stream().allMatch(moment -> moment.chatEvidence().isEmpty()));
    }

    @Test
    void missingVodAndInvalidOffsetAreExplicitlyUnaligned() {
        sessions.recordOnline(OWNER, STREAM, START);
        videos.response = null;
        var unknown = service.extract("clip-1");
        assertEquals("unknown", unknown.association());
        assertEquals("unaligned", unknown.chatCorrelation());

        clips.response = new TwitchClipsApiResponse(List.of(clip(-1, 30)), null);
        var invalidOffset = service.extract("clip-1");
        assertEquals("unaligned", invalidOffset.chatCorrelation());
        assertTrue(invalidOffset.chatCorrelationExplanation().contains("offset"));
    }

    @Test
    void suppliesOrdinaryCurrentSessionChatAsUntimedContextWhenClipHasNoCues() throws Exception {
        sessions.recordOnline(OWNER, STREAM, START);
        clips.response = new TwitchClipsApiResponse(List.of(clip(-1, 30)), null);
        transcription.response = new ClipTranscriptionResponse("clip-1", "", List.of());
        addChat("How is everyone's day?", "just chatting", "hello there");
        addChatFor("other-broadcaster", STREAM, "must not leak");
        addChatFor(OWNER, "older-stream", "not the current session");

        var result = service.extract("clip-1");

        assertTrue(result.moments().isEmpty());
        assertEquals("unknown", result.sessionChatContext().association());
        assertEquals(3, result.sessionChatContext().availableCount());
        assertEquals(3, result.sessionChatContext().analyzedCount());
        assertEquals(3, result.sessionChatContext().suppliedCount());
        assertEquals(0, result.sessionChatContext().truncatedCount());
        assertEquals(java.util.Set.of("How is everyone's day?", "just chatting", "hello there"),
                result.sessionChatContext().messages().stream().map(MemorableMomentsResponse.SessionChatMessage::text)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.chatCorrelationExplanation().contains("untimed background context"));
        assertTrue(result.chatCorrelationExplanation().contains("not clip evidence"));
    }

    @Test
    void unalignedSessionContextIsBoundedDeterministicAndCountsOmittedMessages() throws Exception {
        sessions.recordOnline(OWNER, STREAM, START);
        clips.response = new TwitchClipsApiResponse(List.of(clip(-1, 30)), null);
        for (int i = 0; i < 510; i++) addChatAt(10 + i, "x".repeat(260) + i);

        var first = service.extract("clip-1").sessionChatContext();
        var second = service.extract("clip-1").sessionChatContext();

        assertEquals(510, first.availableCount());
        assertEquals(500, first.analyzedCount());
        assertEquals(20, first.suppliedCount());
        assertEquals(490, first.truncatedCount());
        assertEquals(20, first.messages().size());
        assertTrue(first.messages().stream().allMatch(message -> message.text().length() <= 200));
        assertEquals(first.messages(), second.messages());
    }

    @Test
    void doesNotSupplyCurrentSessionChatForKnownDifferentSessionClip() throws Exception {
        sessions.recordOnline(OWNER, STREAM, START);
        addChat("current session message");
        videos.response = video(VOD, OWNER, "other-stream", "archive", START);

        var result = service.extract("clip-1");

        assertEquals("different_session", result.association());
        assertEquals(0, result.sessionChatContext().availableCount());
        assertEquals(0, result.sessionChatContext().suppliedCount());
        assertTrue(result.chatCorrelationExplanation().contains("omitted for a clip known"));
    }

    @Test void suppliesCompleteFragmentedTranscriptAsSeparateUntimedOrTimedClipContext() {
        sessions.recordOnline(OWNER, STREAM, START);
        clips.response = new TwitchClipsApiResponse(List.of(clip(90, 30)), null);
        List<String> lines = List.of("bullshit application", "how can I", "checking", "break everything", "start", "not know world");
        transcription.response = new ClipTranscriptionResponse("clip-1", String.join(" ", lines),
                java.util.stream.IntStream.range(0, lines.size()).mapToObj(i ->
                        new ClipTranscriptionResponse.TimedTranscriptSegment(i * 2.0, i * 2.0 + 1, lines.get(i))).toList());

        var result = service.extract("clip-1");

        assertTrue(result.moments().isEmpty());
        assertEquals(lines.size(), result.transcriptContext().availableCount());
        assertEquals(lines.size(), result.transcriptContext().suppliedCount());
        assertEquals(0, result.transcriptContext().truncatedCount());
        assertEquals(lines, result.transcriptContext().utterances().stream().map(item -> item.text()).toList());
        assertEquals(0.0, result.transcriptContext().utterances().getFirst().startSeconds());
        assertEquals(11.0, result.transcriptContext().utterances().getLast().endSeconds());
        assertTrue(result.transcriptContext().utterances().stream().allMatch(item -> item.startSeconds() != null && item.endSeconds() != null));
    }

    @Test void transcriptContextBoundsCompleteUtterancesAndNullsInvalidRanges() {
        List<ClipTranscriptionResponse.TimedTranscriptSegment> segments = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) segments.add(new ClipTranscriptionResponse.TimedTranscriptSegment(
                (double) i, i + 0.5, String.format("utterance-%02d ", i) + "x".repeat(385)));
        transcription.response = new ClipTranscriptionResponse("clip-1", "", segments);
        var context = service.extract("clip-1").transcriptContext();
        assertEquals(30, context.availableCount());
        assertEquals(20, context.suppliedCount());
        assertEquals(10, context.truncatedCount());
        assertEquals(7_960, context.suppliedCharacters());
        assertTrue(context.truncatedCharacters() > 0);
        assertEquals(20, context.utterances().size());
        assertTrue(context.utterances().stream().anyMatch(item -> item.text().startsWith("utterance-29")),
                "even sampling retains context from the end rather than taking only the opening utterances");
        assertNotNull(context.utterances().getFirst().startSeconds());
        assertTrue(context.utterances().stream().allMatch(item -> item.text().length() <= 400));
    }

    @Test void invalidTranscriptRangeMakesBothContextTimesUnavailable() {
        transcription.response = new ClipTranscriptionResponse("clip-1", "checking", List.of(
                new ClipTranscriptionResponse.TimedTranscriptSegment(15.0, 2.0, "checking")));
        var item = service.extract("clip-1").transcriptContext().utterances().getFirst();
        assertNull(item.startSeconds());
        assertNull(item.endSeconds());
    }

    @Test void boundedFullTextFallbackIsSplitWithoutLosingContentUnderTheLimit() {
        String fullText = "checking the broken app; " + "trying to fix each small thing; ".repeat(10);
        transcription.response = new ClipTranscriptionResponse("clip-1", fullText, List.of());
        var context = service.extract("clip-1").transcriptContext();
        assertEquals("full_text_fallback", context.sourceType());
        assertEquals((fullText.length() + 399) / 400, context.availableCount());
        assertEquals(context.availableCount(), context.suppliedCount());
        assertEquals(fullText.strip(), context.utterances().stream().map(item -> item.text()).collect(java.util.stream.Collectors.joining()));
        assertTrue(context.utterances().stream().allMatch(item -> item.startSeconds() == null && item.endSeconds() == null));
    }

    @Test
    void rejectsInvalidDurationAndUnownedOrMissingClipSafely() {
        clips.response = new TwitchClipsApiResponse(List.of(clip(90, 0)), null);
        var invalidDuration = assertThrows(TwitchClipMomentsService.MomentsFailure.class,
                () -> service.extract("clip-1"));
        assertEquals("twitch_clip_metadata_invalid", invalidDuration.reason());

        clips.response = new TwitchClipsApiResponse(List.of(
                new TwitchClipsApiResponse.Clip("clip-1", "other", "Title", "creator", START.toString(), 30,
                        "", "", VOD, 90)), null);
        var unowned = assertThrows(TwitchClipMomentsService.MomentsFailure.class, () -> service.extract("clip-1"));
        assertEquals("twitch_clip_not_found", unowned.reason());
        assertEquals(0, transcription.calls);
    }

    @Test
    void rejectsInvalidClipIdBeforeUpstreamCalls() {
        var failure = assertThrows(TwitchClipMomentsService.MomentsFailure.class,
                () -> service.extract("https://example.test/clip"));
        assertEquals("invalid_clip_id", failure.reason());
        assertEquals(0, clips.calls);
        assertEquals(0, videos.calls);
        assertEquals(0, transcription.calls);
    }

    private void addChat(String... texts) throws Exception { addChatAt(95, texts); }

    private void addChatAt(long secondsAfterStart, String... texts) throws Exception {
        int index = 0;
        for (String text : texts) {
            var event = JsonMapper.builder().build().readTree("""
                    {"message_id":"msg-%d-%d","broadcaster_user_id":"%s","chatter_user_id":"user-%d",
                    "chatter_user_login":"viewer%d","message":{"text":"%s"}}
                    """.formatted(secondsAfterStart, index, OWNER, index, index, text));
            messages.record(event, STREAM, START.plusSeconds(secondsAfterStart));
            index++;
        }
    }

    private void addChatFor(String broadcaster, String stream, String text) throws Exception {
        var event = JsonMapper.builder().build().readTree("""
                {"message_id":"%s-%s","broadcaster_user_id":"%s","chatter_user_id":"other-user",
                "chatter_user_login":"other-viewer","message":{"text":"%s"}}
                """.formatted(broadcaster, stream, broadcaster, text));
        messages.record(event, stream, START.plusSeconds(30));
    }

    private static TwitchClipsApiResponse.Clip clip(int offset, double duration) {
        return new TwitchClipsApiResponse.Clip("clip-1", OWNER, "Title", "creator", START.toString(), duration,
                "https://clips.twitch.tv/clip-1", "", VOD, offset);
    }

    private static TwitchVideosApiResponse video(String id, String owner, String stream, String type, Instant created) {
        return new TwitchVideosApiResponse(List.of(new TwitchVideosApiResponse.Video(id, stream, owner, type,
                created.toString(), "2h0m0s")));
    }

    private static final class StubClips extends TwitchClipsApiClient {
        private TwitchClipsApiResponse response;
        private int calls;
        private StubClips(TwitchOAuthProperties properties, RestTemplate rest) {
            super(rest, properties, new TwitchAppAccessTokenService(properties, rest));
        }
        @Override public TwitchClipsApiResponse getClipById(String clipId) { calls++; return response; }
    }

    private static final class StubVideos extends TwitchVideosApiClient {
        private TwitchVideosApiResponse response;
        private int calls;
        private StubVideos(TwitchOAuthProperties properties, RestTemplate rest) {
            super(rest, properties, new TwitchAppAccessTokenService(properties, rest));
        }
        @Override public TwitchVideosApiResponse getVideosByIds(List<String> ids) { calls++; return response; }
    }

    private static final class StubTranscription extends ClipTranscriptionService {
        private ClipTranscriptionResponse response;
        private int calls;
        private StubTranscription() { super(null, null, null); }
        @Override public ClipTranscriptionResponse transcribe(String clipId) { calls++; return response; }
    }
}
