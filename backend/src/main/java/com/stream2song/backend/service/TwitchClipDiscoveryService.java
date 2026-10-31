package com.stream2song.backend.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
import com.stream2song.backend.dto.TwitchClipResponse;
import com.stream2song.backend.dto.TwitchClipsResponse;
import com.stream2song.backend.model.StreamSessionState;

@Service
public class TwitchClipDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(TwitchClipDiscoveryService.class);
    private static final Duration MAX_RANGE = Duration.ofDays(7);
    private static final String ASSOCIATION = "recent_broadcaster_clips";
    private static final String ASSOCIATION_NOTE = "A clip is associated with a session only when Twitch resolves its VOD to the session's stream ID. Other results remain recent broadcaster clips; clip creation time does not identify when the captured moment happened.";

    private final TwitchBroadcasterProvider broadcasterProvider;
    private final TwitchClipsApiClient clipsApiClient;
    private final TwitchVideosApiClient videosApiClient;
    private final TwitchStreamSessionService sessionService;

    public TwitchClipDiscoveryService(TwitchBroadcasterProvider broadcasterProvider, TwitchClipsApiClient clipsApiClient,
            TwitchVideosApiClient videosApiClient, TwitchStreamSessionService sessionService) {
        this.broadcasterProvider = broadcasterProvider;
        this.clipsApiClient = clipsApiClient;
        this.videosApiClient = videosApiClient;
        this.sessionService = sessionService;
    }

    public TwitchClipsResponse discover(Integer requestedLimit, String cursor, Instant requestedStart, Instant requestedEnd) {
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        if ((requestedStart == null) != (requestedEnd == null)) {
            throw new IllegalArgumentException("startedAt and endedAt must be provided together");
        }
        Instant end = requestedEnd == null ? Instant.now() : requestedEnd;
        Instant start = requestedStart == null ? end.minus(MAX_RANGE) : requestedStart;
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_RANGE) > 0 || end.isAfter(Instant.now())) {
            throw new IllegalArgumentException("time range must be positive, at most 7 days, and not in the future");
        }
        if (cursor != null && (cursor.isBlank() || cursor.length() > 1024)) {
            throw new IllegalArgumentException("after must be a non-empty Twitch pagination cursor of at most 1024 characters");
        }

        String broadcasterId = broadcasterProvider.connectedBroadcasterId();
        if (broadcasterId == null || broadcasterId.isBlank()) {
            throw new ClipDiscoveryFailure(HttpStatus.CONFLICT, "twitch_account_not_connected");
        }

        log.info("Starting Twitch clip discovery: broadcasterId={} limit={} startedAt={} endedAt={}",
                broadcasterId, limit, start, end);
        try {
            TwitchClipsApiResponse result = clipsApiClient.getBroadcasterClips(broadcasterId, limit, cursor, start, end);
            List<TwitchClipsApiResponse.Clip> sourceClips = result == null || result.data() == null
                    ? List.of() : result.data();
            StreamSessionState targetSession = sessionService.currentFor(broadcasterId);
            Map<String, String> vodStreamIds = targetSession == null || targetSession.twitchStreamId() == null
                    || targetSession.twitchStreamId().isBlank() ? Map.of()
                    : resolveVodStreamIds(sourceClips, broadcasterId);
            List<TwitchClipResponse> clips = sourceClips.stream()
                    .map(clip -> toResponse(clip, targetSession, vodStreamIds)).toList();
            String nextCursor = result == null || result.pagination() == null ? null : result.pagination().cursor();
            log.info("Twitch clip discovery completed: broadcasterId={} clipsFound={} hasMore={}",
                    broadcasterId, clips.size(), nextCursor != null && !nextCursor.isBlank());
            return new TwitchClipsResponse(broadcasterId, ASSOCIATION,
                    targetSession == null ? null : targetSession.twitchStreamId(),
                    targetSession == null ? null : targetSession.eventTimestamp(), start, end,
                    nextCursor == null || nextCursor.isBlank() ? null : nextCursor, ASSOCIATION_NOTE, clips);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            log.warn("Twitch clip discovery failed: broadcasterId={} httpStatus={}", broadcasterId, status);
            if (status == 401) throw new ClipDiscoveryFailure(HttpStatus.BAD_GATEWAY, "twitch_app_token_rejected");
            if (status == 403) throw new ClipDiscoveryFailure(HttpStatus.BAD_GATEWAY, "twitch_clip_permission_denied");
            if (status == 429) throw new ClipDiscoveryFailure(HttpStatus.TOO_MANY_REQUESTS, "twitch_rate_limited");
            throw new ClipDiscoveryFailure(HttpStatus.BAD_GATEWAY, "twitch_api_request_failed");
        } catch (IllegalStateException exception) {
            log.warn("Twitch clip discovery is unavailable: broadcasterId={} reason={}", broadcasterId, exception.getMessage());
            throw new ClipDiscoveryFailure(HttpStatus.SERVICE_UNAVAILABLE, "twitch_configuration_or_token_unavailable");
        } catch (RestClientException exception) {
            log.warn("Twitch clip discovery is unavailable: broadcasterId={} reason={}", broadcasterId,
                    exception.getClass().getSimpleName());
            throw new ClipDiscoveryFailure(HttpStatus.SERVICE_UNAVAILABLE, "twitch_api_unavailable");
        } catch (RuntimeException exception) {
            log.warn("Twitch clip discovery received an unusable response: broadcasterId={} reason={}",
                    broadcasterId, exception.getClass().getSimpleName());
            throw new ClipDiscoveryFailure(HttpStatus.BAD_GATEWAY, "twitch_response_invalid");
        }
    }

    private Map<String, String> resolveVodStreamIds(List<TwitchClipsApiResponse.Clip> clips, String broadcasterId) {
        Set<String> vodIds = clips.stream().map(TwitchClipsApiResponse.Clip::videoId)
                .filter(id -> id != null && !id.isBlank()).collect(Collectors.toSet());
        if (vodIds.isEmpty()) return Map.of();
        try {
            TwitchVideosApiResponse response = videosApiClient.getVideosByIds(List.copyOf(vodIds));
            if (response == null || response.data() == null) return Map.of();
            return response.data().stream().filter(video -> video.id() != null && video.streamId() != null
                            && !video.streamId().isBlank())
                    .collect(Collectors.toMap(TwitchVideosApiResponse.Video::id,
                            TwitchVideosApiResponse.Video::streamId, (first, ignored) -> first));
        } catch (RestClientResponseException exception) {
            log.warn("Could not resolve Twitch clip VOD associations: broadcasterId={} httpStatus={}",
                    broadcasterId, exception.getStatusCode().value());
            return Map.of();
        } catch (RestClientException | IllegalStateException exception) {
            log.warn("Could not resolve Twitch clip VOD associations: broadcasterId={} reason={}",
                    broadcasterId, exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    private static TwitchClipResponse toResponse(TwitchClipsApiResponse.Clip clip,
            StreamSessionState targetSession, Map<String, String> vodStreamIds) {
        String sessionAssociation = "unknown";
        if (targetSession != null && targetSession.twitchStreamId() != null && !targetSession.twitchStreamId().isBlank()) {
            String clipStreamId = vodStreamIds.get(clip.videoId());
            if (clipStreamId != null) {
                sessionAssociation = clipStreamId.equals(targetSession.twitchStreamId())
                        ? "verified_target_session" : "different_session";
            }
        }
        return new TwitchClipResponse(clip.id(), clip.title(), clip.creatorName(),
                Instant.parse(clip.createdAt()), clip.duration(), clip.url(), clip.thumbnailUrl(),
                clip.videoId() == null || clip.videoId().isBlank() ? null : clip.videoId(),
                clip.vodOffset(), sessionAssociation);
    }

    public static class ClipDiscoveryFailure extends RuntimeException {
        private final HttpStatus status;
        private final String reason;
        public ClipDiscoveryFailure(HttpStatus status, String reason) {
            super(reason);
            this.status = status;
            this.reason = reason;
        }
        public HttpStatus status() { return status; }
        public String reason() { return reason; }
    }
}
