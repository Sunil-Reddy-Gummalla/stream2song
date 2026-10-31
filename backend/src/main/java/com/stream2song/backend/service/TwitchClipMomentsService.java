package com.stream2song.backend.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.stream2song.backend.client.TwitchClipsApiClient;
import com.stream2song.backend.client.TwitchClipsApiResponse;
import com.stream2song.backend.client.TwitchVideosApiClient;
import com.stream2song.backend.client.TwitchVideosApiResponse;
import com.stream2song.backend.dto.ClipTranscriptionResponse;
import com.stream2song.backend.dto.MemorableMomentsResponse;
import com.stream2song.backend.dto.MemorableMomentsResponse.SessionChatContext;
import com.stream2song.backend.dto.MemorableMomentsResponse.TranscriptContext;
import com.stream2song.backend.dto.MemorableMomentsResponse.TranscriptUtterance;
import com.stream2song.backend.dto.MemorableMomentsResponse.SessionChatMessage;
import com.stream2song.backend.dto.TwitchChatHighlightsResponse;
import com.stream2song.backend.model.StreamSessionState;
import com.stream2song.backend.model.TwitchChatMessage;

@Service
public class TwitchClipMomentsService {
    private static final Logger log = LoggerFactory.getLogger(TwitchClipMomentsService.class);
    private static final String SCORE_SCALE = "0–100 deterministic evidence ranking, not probability";
    private static final String CHAT_TIMING_BASIS = "eventsub_delivery_timestamp";
    private static final String CHAT_CAVEAT = "Chat windows use signed EventSub delivery timestamps and are coarse context; delivery may lag speech and is not proof of simultaneous audio/video activity.";
    private static final int MAX_UNALIGNED_CHAT_SCAN = 500;
    private static final int MAX_UNALIGNED_CHAT_SAMPLES = 20;
    private static final int MAX_UNALIGNED_CHAT_SAMPLE_CHARS = 200;
    private static final int MAX_TRANSCRIPT_CONTEXT_CHUNKS = 20;
    private static final int MAX_TRANSCRIPT_CONTEXT_CHARS = 8_000;
    private static final int MAX_TRANSCRIPT_CONTEXT_CHUNK_CHARS = 400;

    private final TwitchBroadcasterProvider broadcasterProvider;
    private final TwitchClipsApiClient clipsApiClient;
    private final TwitchVideosApiClient videosApiClient;
    private final TwitchStreamSessionService sessionService;
    private final TwitchChatMessageService chatMessageService;
    private final TwitchChatHighlightService chatHighlightService;
    private final ClipTranscriptionService transcriptionService;
    private final MemorableMomentExtractionEngine extractionEngine;
    private final MomentAnalysisSnapshotService snapshotService;

    @Autowired
    public TwitchClipMomentsService(TwitchBroadcasterProvider broadcasterProvider, TwitchClipsApiClient clipsApiClient,
            TwitchVideosApiClient videosApiClient, TwitchStreamSessionService sessionService,
            TwitchChatMessageService chatMessageService, TwitchChatHighlightService chatHighlightService,
            ClipTranscriptionService transcriptionService,
            MemorableMomentExtractionEngine extractionEngine, MomentAnalysisSnapshotService snapshotService) {
        this.broadcasterProvider = broadcasterProvider;
        this.clipsApiClient = clipsApiClient;
        this.videosApiClient = videosApiClient;
        this.sessionService = sessionService;
        this.chatMessageService = chatMessageService;
        this.chatHighlightService = chatHighlightService;
        this.transcriptionService = transcriptionService;
        this.extractionEngine = extractionEngine;
        this.snapshotService = snapshotService;
    }

    public TwitchClipMomentsService(TwitchBroadcasterProvider broadcasterProvider, TwitchClipsApiClient clipsApiClient,
            TwitchVideosApiClient videosApiClient, TwitchStreamSessionService sessionService,
            TwitchChatMessageService chatMessageService, TwitchChatHighlightService chatHighlightService,
            ClipTranscriptionService transcriptionService, MemorableMomentExtractionEngine extractionEngine) {
        this(broadcasterProvider, clipsApiClient, videosApiClient, sessionService, chatMessageService,
                chatHighlightService, transcriptionService, extractionEngine, new MomentAnalysisSnapshotService());
    }

    public MemorableMomentsResponse extract(String clipId) {
        if (clipId == null || !clipId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new MomentsFailure(HttpStatus.BAD_REQUEST, "invalid_clip_id");
        }
        TwitchBroadcasterProvider.ConnectionScope connection = broadcasterProvider.connectionScope();
        String broadcasterId = connection == null ? null : connection.broadcasterId();
        if (broadcasterId == null || broadcasterId.isBlank()) {
            throw new MomentsFailure(HttpStatus.CONFLICT, "twitch_account_not_connected");
        }
        long analysisRevision = snapshotService.revision(connection, clipId);
        TwitchClipsApiResponse.Clip clip = selectedClip(clipId, broadcasterId);
        ClipTranscriptionResponse transcript = transcriptionService.transcribe(clipId);

        StreamSessionState session = sessionService.currentFor(broadcasterId);
        TwitchVideosApiResponse.Video vod = resolveVod(clip.videoId());
        String association = association(vod, broadcasterId, session);
        Alignment alignment = alignment(clip, vod, broadcasterId, session);
        Instant streamStartedAt = alignment.aligned && session != null ? session.streamStartedAt() : null;
        List<TwitchChatMessage> messages = alignment.aligned && session != null
                ? chatMessageService.allForStream(broadcasterId, session.twitchStreamId()) : List.of();
        List<TwitchChatHighlightsResponse.Highlight> chatHighlights = alignment.aligned
                ? chatHighlightService.analyzeClipRange(messages, session.twitchStreamId(),
                        streamStartedAt.plusSeconds(clip.vodOffset()),
                        streamStartedAt.plusNanos((long) (clip.vodOffset() * 1_000_000_000.0
                                + clip.duration() * 1_000_000_000.0)), 10).highlights()
                : List.of();
        SessionChatContext sessionChatContext = !alignment.aligned && !"different_session".equals(association)
                ? recentSessionChatContext(broadcasterId, session) : SessionChatContext.empty();
        TranscriptContext transcriptContext = transcriptContext(transcript, clip.duration(), association);
        List<MemorableMomentsResponse.Moment> moments = extractionEngine.extract(clipId, clip.duration(), association,
                transcript.text(), transcript.segments(), chatHighlights, streamStartedAt, clip.vodOffset(),
                "verified_target_session".equals(association));
        var source = new MemorableMomentsResponse.SourceClip(clip.title(), clip.creatorName(), clip.duration(),
                clip.videoId(), clip.vodOffset());
        String correlation = alignment.aligned ? "aligned_coarse_context" : "unaligned";
        String explanation = alignment.aligned ? CHAT_CAVEAT : unalignedChatExplanation(alignment.reason, association, sessionChatContext);
        String message = moments.isEmpty() && transcriptContext.suppliedCount() > 0
                ? "No clip-timed moments were found. Transcript utterances are available as clip context, not scored moments."
                : moments.isEmpty() && sessionChatContext.suppliedCount() > 0
                ? "No clip-timed moments were found. Recent session chat is available only as untimed background context."
                : moments.isEmpty() ? "No supported transcript or aligned chat cues were found for this clip." : null;
        MemorableMomentsResponse response = new MemorableMomentsResponse(UUID.randomUUID().toString(), clipId, source, association, correlation,
                alignment.aligned ? CHAT_TIMING_BASIS : null, explanation, true, SCORE_SCALE, message, moments, sessionChatContext, transcriptContext);
        if (!broadcasterProvider.isCurrentConnection(connection)) throw new MomentsFailure(HttpStatus.CONFLICT, "twitch_account_changed");
        snapshotService.store(connection, response, analysisRevision);
        return response;
    }

    public void invalidate(String clipId) {
        snapshotService.invalidate(broadcasterProvider.connectedBroadcasterId(), clipId);
    }

    public void invalidate(String broadcasterId, String clipId) {
        snapshotService.invalidate(broadcasterId, clipId);
    }

    private TwitchClipsApiResponse.Clip selectedClip(String clipId, String broadcasterId) {
        try {
            TwitchClipsApiResponse result = clipsApiClient.getClipById(clipId);
            if (result == null || result.data() == null) throw new MomentsFailure(HttpStatus.NOT_FOUND, "twitch_clip_not_found");
            TwitchClipsApiResponse.Clip clip = result.data().stream().filter(item -> clipId.equals(item.id())).findFirst()
                    .orElseThrow(() -> new MomentsFailure(HttpStatus.NOT_FOUND, "twitch_clip_not_found"));
            if (!broadcasterId.equals(clip.broadcasterId())) {
                throw new MomentsFailure(HttpStatus.NOT_FOUND, "twitch_clip_not_found");
            }
            if (!Double.isFinite(clip.duration()) || clip.duration() <= 0 || clip.duration() > 300) {
                throw new MomentsFailure(HttpStatus.BAD_GATEWAY, "twitch_clip_metadata_invalid");
            }
            return clip;
        } catch (MomentsFailure failure) {
            throw failure;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401) throw new MomentsFailure(HttpStatus.BAD_GATEWAY, "twitch_app_token_rejected");
            if (status == 429) throw new MomentsFailure(HttpStatus.TOO_MANY_REQUESTS, "twitch_rate_limited");
            throw new MomentsFailure(HttpStatus.BAD_GATEWAY, "twitch_clip_metadata_unavailable");
        } catch (RestClientException | IllegalStateException exception) {
            throw new MomentsFailure(HttpStatus.SERVICE_UNAVAILABLE, "twitch_clip_metadata_unavailable");
        }
    }

    private TwitchVideosApiResponse.Video resolveVod(String vodId) {
        if (vodId == null || vodId.isBlank()) return null;
        try {
            TwitchVideosApiResponse response = videosApiClient.getVideosByIds(List.of(vodId));
            return response == null || response.data() == null ? null : response.data().stream()
                    .filter(video -> vodId.equals(video.id())).findFirst().orElse(null);
        } catch (RestClientException | IllegalStateException exception) {
            log.info("Twitch clip VOD metadata unavailable for moment alignment");
            return null;
        }
    }

    private String association(TwitchVideosApiResponse.Video vod, String broadcasterId, StreamSessionState session) {
        if (vod == null || !broadcasterId.equals(vod.userId()) || vod.streamId() == null || vod.streamId().isBlank()
                || session == null || session.twitchStreamId() == null || session.twitchStreamId().isBlank()) return "unknown";
        return vod.streamId().equals(session.twitchStreamId()) ? "verified_target_session" : "different_session";
    }

    private Alignment alignment(TwitchClipsApiResponse.Clip clip, TwitchVideosApiResponse.Video vod,
            String broadcasterId, StreamSessionState session) {
        if (clip.vodOffset() == null || clip.vodOffset() < 0 || !Double.isFinite(clip.duration()) || clip.duration() <= 0) {
            return new Alignment(false, "clip VOD offset or duration is unavailable");
        }
        if (vod == null) return new Alignment(false, "VOD metadata is unavailable");
        if (!broadcasterId.equals(vod.userId())) return new Alignment(false, "VOD owner does not match connected broadcaster");
        if (!"archive".equals(vod.type())) return new Alignment(false, "VOD is not an archive");
        if (session == null || session.twitchStreamId() == null || session.streamStartedAt() == null) {
            return new Alignment(false, "stored Twitch stream ID or start time is unavailable");
        }
        if (!session.twitchStreamId().equals(vod.streamId())) return new Alignment(false, "VOD stream does not match stored session");
        try {
            Instant vodCreatedAt = Instant.parse(vod.createdAt());
            if (vodCreatedAt.getEpochSecond() != session.streamStartedAt().getEpochSecond()) {
                return new Alignment(false, "VOD creation time does not match Twitch stream start time");
            }
        } catch (DateTimeParseException | NullPointerException exception) {
            return new Alignment(false, "VOD creation time is unavailable");
        }
        return new Alignment(true, "all alignment checks passed");
    }

    private SessionChatContext recentSessionChatContext(String broadcasterId, StreamSessionState session) {
        if (session == null || session.twitchStreamId() == null || session.twitchStreamId().isBlank()) {
            return SessionChatContext.empty();
        }
        long available = chatMessageService.count(broadcasterId, session.twitchStreamId());
        List<TwitchChatMessage> recent = chatMessageService.latest(broadcasterId, session.twitchStreamId(), MAX_UNALIGNED_CHAT_SCAN);
        List<TwitchChatMessage> exemplars = recent.size() <= MAX_UNALIGNED_CHAT_SAMPLES ? recent
                : recent.subList(recent.size() - MAX_UNALIGNED_CHAT_SAMPLES, recent.size());
        List<SessionChatMessage> supplied = new java.util.ArrayList<>();
        for (TwitchChatMessage message : exemplars) {
            if (message.text() == null || message.text().isBlank()) continue;
            String text = message.text().strip();
            if (text.length() > MAX_UNALIGNED_CHAT_SAMPLE_CHARS) text = text.substring(0, MAX_UNALIGNED_CHAT_SAMPLE_CHARS);
            supplied.add(new SessionChatMessage("session-chat-" + (supplied.size() + 1), text));
        }
        long truncated = Math.max(0, available - supplied.size());
        return new SessionChatContext(available, recent.size(), supplied.size(), truncated, List.copyOf(supplied));
    }

    private String unalignedChatExplanation(String reason, String association, SessionChatContext context) {
        String base = "Chat cannot be aligned to the clip timeline: " + reason + ".";
        if ("different_session".equals(association)) {
            return base + " Chat from the connected session is omitted for a clip known to belong to a different stream.";
        }
        if (context.suppliedCount() > 0) {
            return base + " Added " + context.suppliedCount() + " text-only samples from the connected broadcaster's most recent stored stream session as untimed background context, not clip evidence; "
                    + context.truncatedCount() + " of " + context.availableCount() + " stored messages were omitted. Session context does not establish clip events, timing, or causality.";
        }
        return base + " No recent stored session chat is available; collection must have been running earlier. No Twitch chat history is fetched.";
    }

    private TranscriptContext transcriptContext(ClipTranscriptionResponse transcript, double duration, String association) {
        List<ClipTranscriptionResponse.TimedTranscriptSegment> source = transcript.segments() == null ? List.of() : transcript.segments();
        List<ClipTranscriptionResponse.TimedTranscriptSegment> usable = source.stream()
                .filter(segment -> segment != null && segment.text() != null && !segment.text().isBlank()).toList();
        long available = usable.size();
        long totalCharacters = usable.stream().mapToLong(segment -> segment.text().strip().length()).sum();
        List<ClipTranscriptionResponse.TimedTranscriptSegment> selected = new java.util.ArrayList<>();
        if (usable.size() <= MAX_TRANSCRIPT_CONTEXT_CHUNKS) selected.addAll(usable);
        else for (int i = 0; i < MAX_TRANSCRIPT_CONTEXT_CHUNKS; i++) {
            int index = (int) Math.round((double) i * (usable.size() - 1) / (MAX_TRANSCRIPT_CONTEXT_CHUNKS - 1));
            selected.add(usable.get(index));
        }
        List<TranscriptUtterance> supplied = new java.util.ArrayList<>();
        int chars = 0;
        for (var segment : selected) {
            if (supplied.size() >= MAX_TRANSCRIPT_CONTEXT_CHUNKS || chars >= MAX_TRANSCRIPT_CONTEXT_CHARS) break;
            String text = segment.text().strip();
            if (text.length() > MAX_TRANSCRIPT_CONTEXT_CHUNK_CHARS
                    || text.length() > MAX_TRANSCRIPT_CONTEXT_CHARS - chars) continue;
            boolean validRange = segment.startSeconds() != null && segment.endSeconds() != null
                    && Double.isFinite(segment.startSeconds()) && Double.isFinite(segment.endSeconds())
                    && segment.startSeconds() >= 0 && segment.endSeconds() >= segment.startSeconds()
                    && segment.endSeconds() <= duration;
            supplied.add(new TranscriptUtterance("clip-transcript-" + (supplied.size() + 1), text,
                    validRange ? segment.startSeconds() : null, validRange ? segment.endSeconds() : null));
            chars += text.length();
        }
        long truncatedCharacters = Math.max(0, totalCharacters - chars);
        String sourceType = "utterances";
        if (available == 0 && transcript.text() != null && !transcript.text().isBlank()) {
            String text = transcript.text().strip();
            sourceType = "full_text_fallback";
            totalCharacters = text.length();
            available = (text.length() + MAX_TRANSCRIPT_CONTEXT_CHUNK_CHARS - 1) / MAX_TRANSCRIPT_CONTEXT_CHUNK_CHARS;
            int capturedLength = Math.min(text.length(), MAX_TRANSCRIPT_CONTEXT_CHARS);
            for (int offset = 0; offset < capturedLength; offset += MAX_TRANSCRIPT_CONTEXT_CHUNK_CHARS) {
                int end = Math.min(capturedLength, offset + MAX_TRANSCRIPT_CONTEXT_CHUNK_CHARS);
                supplied.add(new TranscriptUtterance("clip-transcript-" + (supplied.size() + 1), text.substring(offset, end), null, null));
                chars += end - offset;
            }
            truncatedCharacters = Math.max(0, totalCharacters - chars);
        }
        return new TranscriptContext(available, available, supplied.size(), Math.max(0, available - supplied.size()),
                chars, truncatedCharacters, association == null ? "unknown" : association, sourceType, List.copyOf(supplied));
    }

    private record Alignment(boolean aligned, String reason) { }

    public static class MomentsFailure extends RuntimeException {
        private final HttpStatus status;
        private final String reason;
        public MomentsFailure(HttpStatus status, String reason) { super(reason); this.status = status; this.reason = reason; }
        public HttpStatus status() { return status; }
        public String reason() { return reason; }
    }
}
