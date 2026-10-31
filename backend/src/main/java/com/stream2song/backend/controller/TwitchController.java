package com.stream2song.backend.controller;

import java.net.URI;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import com.stream2song.backend.dto.TwitchConnectionStatusResponse;
import com.stream2song.backend.dto.TwitchStreamSessionResponse;
import com.stream2song.backend.dto.TwitchChatSummaryResponse;
import com.stream2song.backend.dto.TwitchChatHighlightsResponse;
import com.stream2song.backend.dto.TwitchClipsResponse;
import com.stream2song.backend.dto.TwitchClipMediaResponse;
import com.stream2song.backend.dto.ClipTranscriptionResponse;
import com.stream2song.backend.dto.MemorableMomentsResponse;
import com.stream2song.backend.dto.MomentInterpretationResponse;
import com.stream2song.backend.dto.ClipLyricsResponse;
import com.stream2song.backend.dto.MusicJobResponse;
import com.stream2song.backend.model.TwitchChatMessage;
import com.stream2song.backend.service.TwitchChatCollectionService;
import com.stream2song.backend.service.TwitchChatDashboardService;
import com.stream2song.backend.service.TwitchChatHighlightService;
import com.stream2song.backend.service.TwitchClipDiscoveryService;
import com.stream2song.backend.service.TwitchClipMediaService;
import com.stream2song.backend.service.ClipTranscriptionService;
import com.stream2song.backend.service.TwitchClipMomentsService;
import com.stream2song.backend.service.TwitchDashboardService;
import com.stream2song.backend.service.TwitchEventSubSubscriptionService;
import com.stream2song.backend.service.TwitchOAuthFailure;
import com.stream2song.backend.service.TwitchOAuthService;
import com.stream2song.backend.service.MomentInterpretationService;
import com.stream2song.backend.service.ClipLyricsGenerationService;
import com.stream2song.backend.service.MusicJobService;
import com.stream2song.backend.service.SpokenLyricsService;

@RestController
@RequestMapping("/api/twitch")
public class TwitchController {
    private static final Logger log = LoggerFactory.getLogger(TwitchController.class);
    private final TwitchOAuthService twitchOAuthService;
    private final TwitchEventSubSubscriptionService subscriptionService;
    private final TwitchDashboardService dashboardService;
    private final TwitchChatCollectionService chatCollectionService;
    private final TwitchChatDashboardService chatDashboardService;
    private final TwitchChatHighlightService chatHighlightService;
    private final TwitchClipDiscoveryService clipDiscoveryService;
    private final TwitchClipMediaService clipMediaService;
    private final ClipTranscriptionService clipTranscriptionService;
    private final TwitchClipMomentsService clipMomentsService;
    private final MomentInterpretationService momentInterpretationService;
    private final ClipLyricsGenerationService lyricsGenerationService;
    private final MusicJobService musicJobService;
    private final SpokenLyricsService spokenLyricsService;

    @org.springframework.beans.factory.annotation.Autowired
    public TwitchController(
            TwitchOAuthService twitchOAuthService,
            TwitchEventSubSubscriptionService subscriptionService,
            TwitchDashboardService dashboardService,
            TwitchChatCollectionService chatCollectionService,
            TwitchChatDashboardService chatDashboardService,
            TwitchChatHighlightService chatHighlightService,
            TwitchClipDiscoveryService clipDiscoveryService,
            TwitchClipMediaService clipMediaService,
            ClipTranscriptionService clipTranscriptionService,
            TwitchClipMomentsService clipMomentsService,
            MomentInterpretationService momentInterpretationService,
            ClipLyricsGenerationService lyricsGenerationService,
            MusicJobService musicJobService,
            SpokenLyricsService spokenLyricsService) {
        this.twitchOAuthService = twitchOAuthService;
        this.subscriptionService = subscriptionService;
        this.dashboardService = dashboardService;
        this.chatCollectionService = chatCollectionService;
        this.chatDashboardService = chatDashboardService;
        this.chatHighlightService = chatHighlightService;
        this.clipDiscoveryService = clipDiscoveryService;
        this.clipMediaService = clipMediaService;
        this.clipTranscriptionService = clipTranscriptionService;
        this.clipMomentsService = clipMomentsService;
        this.momentInterpretationService = momentInterpretationService;
        this.lyricsGenerationService = lyricsGenerationService;
        this.musicJobService = musicJobService;
        this.spokenLyricsService = spokenLyricsService;
    }

    public TwitchController(TwitchOAuthService twitchOAuthService,TwitchEventSubSubscriptionService subscriptionService,
            TwitchDashboardService dashboardService,TwitchChatCollectionService chatCollectionService,TwitchChatDashboardService chatDashboardService,
            TwitchChatHighlightService chatHighlightService,TwitchClipDiscoveryService clipDiscoveryService,TwitchClipMediaService clipMediaService,
            ClipTranscriptionService clipTranscriptionService,TwitchClipMomentsService clipMomentsService,
            MomentInterpretationService momentInterpretationService,ClipLyricsGenerationService lyricsGenerationService,
            MusicJobService musicJobService) {
        this(twitchOAuthService,subscriptionService,dashboardService,chatCollectionService,chatDashboardService,chatHighlightService,
                clipDiscoveryService,clipMediaService,clipTranscriptionService,clipMomentsService,momentInterpretationService,
                lyricsGenerationService,musicJobService,null);
    }

    public TwitchController(TwitchOAuthService twitchOAuthService,TwitchEventSubSubscriptionService subscriptionService,
            TwitchDashboardService dashboardService,TwitchChatCollectionService chatCollectionService,TwitchChatDashboardService chatDashboardService,
            TwitchChatHighlightService chatHighlightService,TwitchClipDiscoveryService clipDiscoveryService,TwitchClipMediaService clipMediaService,
            ClipTranscriptionService clipTranscriptionService,TwitchClipMomentsService clipMomentsService,
            MomentInterpretationService momentInterpretationService,ClipLyricsGenerationService lyricsGenerationService) {
        this(twitchOAuthService,subscriptionService,dashboardService,chatCollectionService,chatDashboardService,chatHighlightService,
                clipDiscoveryService,clipMediaService,clipTranscriptionService,clipMomentsService,momentInterpretationService,lyricsGenerationService,null);
    }

    public TwitchController(TwitchOAuthService twitchOAuthService, TwitchEventSubSubscriptionService subscriptionService,
            TwitchDashboardService dashboardService, TwitchChatCollectionService chatCollectionService,
            TwitchChatDashboardService chatDashboardService, TwitchChatHighlightService chatHighlightService,
            TwitchClipDiscoveryService clipDiscoveryService, TwitchClipMediaService clipMediaService,
            ClipTranscriptionService clipTranscriptionService, TwitchClipMomentsService clipMomentsService,
            MomentInterpretationService momentInterpretationService) {
        this(twitchOAuthService, subscriptionService, dashboardService, chatCollectionService, chatDashboardService,
                chatHighlightService, clipDiscoveryService, clipMediaService, clipTranscriptionService,
                clipMomentsService, momentInterpretationService, null);
    }

    public TwitchController(TwitchOAuthService twitchOAuthService, TwitchEventSubSubscriptionService subscriptionService,
            TwitchDashboardService dashboardService, TwitchChatCollectionService chatCollectionService,
            TwitchChatDashboardService chatDashboardService, TwitchChatHighlightService chatHighlightService,
            TwitchClipDiscoveryService clipDiscoveryService, TwitchClipMediaService clipMediaService,
            ClipTranscriptionService clipTranscriptionService, TwitchClipMomentsService clipMomentsService) {
        this(twitchOAuthService, subscriptionService, dashboardService, chatCollectionService, chatDashboardService,
                chatHighlightService, clipDiscoveryService, clipMediaService, clipTranscriptionService,
                clipMomentsService, null);
    }

    @GetMapping("/connect")
    public ResponseEntity<Void> connect(HttpServletRequest request) {
        try {
            log.info("Starting Twitch OAuth authorization");
            return redirect(twitchOAuthService.createAuthorizationUri(request.getSession(true).getId()));
        } catch (TwitchOAuthFailure failure) {
            log.warn("Could not start Twitch OAuth authorization: {}", failure.reason());
            return redirect(twitchOAuthService.frontendRedirect("error", failure.reason()));
        }
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            HttpServletRequest request) {
        try {
            String sessionId = request.getSession(false) == null ? null : request.getSession(false).getId();
            twitchOAuthService.completeAuthorization(state, code, error, sessionId);
            TwitchConnectionStatusResponse connection = twitchOAuthService.connectionStatus();
            log.info("Twitch account connected for broadcasterId={}", connection.broadcasterId());
            subscriptionService.subscribeToStreamEvents(connection.broadcasterId());
            chatCollectionService.synchronizeCurrentStream(connection.broadcasterId());
            return redirect(twitchOAuthService.frontendRedirect("connected", null));
        } catch (TwitchOAuthFailure failure) {
            log.warn("Twitch OAuth callback failed: {}", failure.reason());
            return redirect(twitchOAuthService.frontendRedirect("error", failure.reason()));
        }
    }

    @GetMapping("/status")
    public TwitchConnectionStatusResponse status() {
        return twitchOAuthService.connectionStatus();
    }

    @GetMapping("/session")
    public TwitchStreamSessionResponse session() {
        return dashboardService.currentSession();
    }

    @org.springframework.web.bind.annotation.PostMapping("/session/refresh")
    public TwitchStreamSessionResponse refreshSession() {
        TwitchConnectionStatusResponse connection = twitchOAuthService.connectionStatus();
        if (!connection.connected()) return TwitchStreamSessionResponse.disconnected();
        log.info("Refreshing Twitch session for broadcasterId={}", connection.broadcasterId());
        subscriptionService.subscribeToStreamEvents(connection.broadcasterId());
        chatCollectionService.synchronizeCurrentStream(connection.broadcasterId());
        log.debug("Twitch session refresh completed for broadcasterId={}", connection.broadcasterId());
        return dashboardService.currentSession();
    }

    @GetMapping("/chat/messages")
    public java.util.List<TwitchChatMessage> chatMessages(
            @RequestParam(defaultValue = "20") int limit) {
        return chatDashboardService.messages(Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping("/chat/summary")
    public TwitchChatSummaryResponse chatSummary() {
        return chatDashboardService.summary();
    }

    @GetMapping("/chat/highlights")
    public TwitchChatHighlightsResponse chatHighlights(
            @RequestParam(defaultValue = "30") int windowSeconds,
            @RequestParam(defaultValue = "10") int limit) {
        if (windowSeconds < 5 || windowSeconds > 300) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "windowSeconds must be between 5 and 300");
        }
        if (limit < 1 || limit > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 20");
        }
        return chatHighlightService.currentStreamHighlights(windowSeconds, limit);
    }

    @GetMapping("/clips")
    public TwitchClipsResponse clips(
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Instant startedAt,
            @RequestParam(required = false) Instant endedAt) {
        try {
            return clipDiscoveryService.discover(limit, after, startedAt, endedAt);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/clips/{clipId}/media")
    public TwitchClipMediaResponse clipMedia(@org.springframework.web.bind.annotation.PathVariable String clipId) {
        return clipMediaService.downloadUrls(clipId);
    }

    @org.springframework.web.bind.annotation.PostMapping("/clips/{clipId}/transcription")
    public ClipTranscriptionResponse transcribeClip(@org.springframework.web.bind.annotation.PathVariable String clipId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean refresh) {
        return clipTranscriptionService.transcribe(clipId, refresh,
                broadcasterId -> clipMomentsService.invalidate(broadcasterId, clipId));
    }

    @org.springframework.web.bind.annotation.PostMapping("/clips/{clipId}/moments")
    public MemorableMomentsResponse clipMoments(@org.springframework.web.bind.annotation.PathVariable String clipId) {
        return clipMomentsService.extract(clipId);
    }

    public record InterpretationRequest(String analysisId) { }

    public record LyricsRequest(String analysisId, String interpretationId, String genre, String mood, String energy) { }

    @org.springframework.web.bind.annotation.PostMapping("/clips/{clipId}/lyrics")
    public ClipLyricsResponse generateClipLyrics(@org.springframework.web.bind.annotation.PathVariable String clipId,
            @org.springframework.web.bind.annotation.RequestBody LyricsRequest request) {
        if (lyricsGenerationService == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "lyrics_provider_not_configured");
        return lyricsGenerationService.generate(clipId, request == null ? null : new ClipLyricsGenerationService.LyricsRequest(
                request.analysisId(), request.interpretationId(), request.genre(), request.mood(), request.energy()));
    }

    public record MusicJobRequest(String lyricsId) { }
    public record MusicRetryRequest(Integer expectedAttempt) { }
    @org.springframework.web.bind.annotation.PostMapping("/clips/{clipId}/music-jobs")
    public ResponseEntity<MusicJobResponse> createMusicJob(@org.springframework.web.bind.annotation.PathVariable String clipId,
            @org.springframework.web.bind.annotation.RequestBody MusicJobRequest request) {
        if(musicJobService==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"music_provider_not_configured");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(musicJobService.create(clipId,request==null?null:request.lyricsId()));
    }
    @GetMapping("/music-jobs/{jobId}")
    public MusicJobResponse getMusicJob(@org.springframework.web.bind.annotation.PathVariable String jobId) {
        if(musicJobService==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"music_provider_not_configured");
        return musicJobService.get(jobId);
    }
    @org.springframework.web.bind.annotation.PostMapping("/music-jobs/{jobId}/retry")
    public ResponseEntity<MusicJobResponse> retryMusicJob(@org.springframework.web.bind.annotation.PathVariable String jobId,
            @org.springframework.web.bind.annotation.RequestBody MusicRetryRequest request) {
        if(musicJobService==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"music_provider_not_configured");
        if(request==null||request.expectedAttempt()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_music_retry_request");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(musicJobService.retry(jobId,request.expectedAttempt()));
    }
    @GetMapping("/music-jobs/{jobId}/audio")
    public ResponseEntity<byte[]> getMusicAudio(@org.springframework.web.bind.annotation.PathVariable String jobId,
            @org.springframework.web.bind.annotation.RequestHeader(value="Range",required=false) String range) {
        if(musicJobService==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"music_provider_not_configured");
        byte[] body=musicJobService.audio(jobId);
        HttpHeaders headers=new HttpHeaders();headers.set("Accept-Ranges","bytes");headers.set("Cache-Control","no-store");headers.setContentType(org.springframework.http.MediaType.valueOf("audio/mpeg"));
        if(range==null)return new ResponseEntity<>(body,headers,HttpStatus.OK);
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range.trim());
        if(!matcher.matches()||range.contains(","))return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).header("Content-Range","bytes */"+body.length).build();
        long start,end;
        try {
            if(matcher.group(1).isEmpty()) { long suffix=Long.parseLong(matcher.group(2));if(suffix<=0)throw new NumberFormatException();start=Math.max(0,body.length-suffix);end=body.length-1; }
            else { start=Long.parseLong(matcher.group(1));end=matcher.group(2).isEmpty()?body.length-1:Long.parseLong(matcher.group(2)); }
        } catch(NumberFormatException invalidRange) { return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).header("Content-Range","bytes */"+body.length).build(); }
        if(start<0||end<start||start>=body.length)return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).header("Content-Range","bytes */"+body.length).build();
        end=Math.min(end,body.length-1);byte[] partial=java.util.Arrays.copyOfRange(body,(int)start,(int)end+1);
        headers.set("Content-Range","bytes "+start+"-"+end+"/"+body.length);headers.setContentLength(partial.length);
        return new ResponseEntity<>(partial,headers,HttpStatus.PARTIAL_CONTENT);
    }

    @org.springframework.web.bind.annotation.PostMapping("/clips/{clipId}/lyrics/{lyricsId}/spoken-preview")
    public ResponseEntity<byte[]> generateSpokenLyricsPreview(
            @org.springframework.web.bind.annotation.PathVariable String clipId,
            @org.springframework.web.bind.annotation.PathVariable String lyricsId) {
        if (spokenLyricsService == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "spoken_preview_not_configured");
        byte[] wav = spokenLyricsService.preview(clipId, lyricsId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.valueOf("audio/wav"));
        headers.setCacheControl("no-store");
        headers.setContentLength(wav.length);
        return new ResponseEntity<>(wav, headers, HttpStatus.OK);
    }

    @org.springframework.web.bind.annotation.PostMapping("/clips/{clipId}/moments/interpretation")
    public MomentInterpretationResponse interpretClipMoments(
            @org.springframework.web.bind.annotation.PathVariable String clipId,
            @org.springframework.web.bind.annotation.RequestBody InterpretationRequest request) {
        if (momentInterpretationService == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "interpretation_provider_not_configured");
        return momentInterpretationService.interpret(clipId, request == null ? null : request.analysisId());
    }

    private ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location.toASCIIString())
                .build();
    }
}
