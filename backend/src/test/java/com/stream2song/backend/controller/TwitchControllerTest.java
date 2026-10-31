package com.stream2song.backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.stream2song.backend.config.TwitchOAuthProperties;
import com.stream2song.backend.config.TwitchEventSubProperties;
import com.stream2song.backend.service.TwitchOAuthService;
import com.stream2song.backend.service.TwitchDashboardService;
import com.stream2song.backend.service.TwitchEventSubSubscriptionRegistry;
import com.stream2song.backend.service.TwitchEventSubSubscriptionService;
import com.stream2song.backend.service.TwitchStreamSessionService;
import com.stream2song.backend.service.TwitchChatCollectionService;
import com.stream2song.backend.service.TwitchChatDashboardService;
import com.stream2song.backend.service.TwitchChatMessageService;
import com.stream2song.backend.service.TwitchChatHighlightService;
import com.stream2song.backend.service.TwitchClipDiscoveryService;
import com.stream2song.backend.client.TwitchClipsApiClient;
import com.stream2song.backend.client.TwitchVideosApiClient;
import com.stream2song.backend.client.TwitchClipDownloadsApiClient;
import com.stream2song.backend.service.TwitchClipMediaService;
import com.stream2song.backend.service.ClipTranscriptionService;
import com.stream2song.backend.dto.ClipTranscriptionResponse;
import com.stream2song.backend.dto.MemorableMomentsResponse;
import com.stream2song.backend.dto.MomentInterpretationResponse;
import com.stream2song.backend.service.TwitchClipMomentsService;
import com.stream2song.backend.service.MomentInterpretationService;
import com.stream2song.backend.service.MusicJobService;
import com.stream2song.backend.service.MusicGenerationFailure;
import com.stream2song.backend.dto.MusicJobResponse;
import com.stream2song.backend.service.SpokenLyricsService;
import com.stream2song.backend.service.SpokenLyricsFailure;
import com.stream2song.backend.config.TwitchEventSubProperties;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TwitchControllerTest {
    private MockMvc mockMvc;
    private MockRestServiceServer twitchServer;
    private ClipTranscriptionService clipTranscriptionService;
    private TwitchClipMomentsService clipMomentsService;
    private StubInterpretationService interpretationService;
    private MusicJobService musicJobService;
    private StubSpokenLyricsService spokenLyricsService;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        twitchServer = MockRestServiceServer.bindTo(restTemplate).build();
        TwitchOAuthProperties oauthProperties = new TwitchOAuthProperties("client-id", "client-secret",
                "http://localhost:8080/api/twitch/oauth/callback", "http://localhost:4200");
        TwitchEventSubSubscriptionRegistry registry = new TwitchEventSubSubscriptionRegistry();
        TwitchStreamSessionService sessions = new TwitchStreamSessionService();
        com.stream2song.backend.service.TwitchAppAccessTokenService appTokens = new com.stream2song.backend.service.TwitchAppAccessTokenService(oauthProperties, restTemplate);
        TwitchEventSubSubscriptionService subscriptions = new TwitchEventSubSubscriptionService(
                oauthProperties, new TwitchEventSubProperties("", ""), registry,
                appTokens, restTemplate);
        TwitchOAuthService service = new TwitchOAuthService(oauthProperties, restTemplate);
        TwitchDashboardService dashboard = new TwitchDashboardService(service, sessions, registry);
        TwitchChatMessageService chatMessages = new TwitchChatMessageService();
        TwitchChatCollectionService chatCollection = new TwitchChatCollectionService(subscriptions, sessions, service);
        TwitchChatDashboardService chatDashboard = new TwitchChatDashboardService(service, sessions, registry, chatMessages);
        TwitchChatHighlightService chatHighlights = new TwitchChatHighlightService(service, sessions, chatMessages);
        TwitchClipDiscoveryService clips = new TwitchClipDiscoveryService(service,
                new TwitchClipsApiClient(restTemplate, oauthProperties, appTokens),
                new TwitchVideosApiClient(restTemplate, oauthProperties, appTokens), sessions);
        TwitchClipMediaService clipMedia = new TwitchClipMediaService(service, new TwitchClipDownloadsApiClient(restTemplate, oauthProperties, service));
        clipTranscriptionService = new StubTranscriptionService();
        clipMomentsService = new StubMomentsService();
        interpretationService = new StubInterpretationService();
        musicJobService = new StubMusicJobService();
        spokenLyricsService = new StubSpokenLyricsService();
        mockMvc = MockMvcBuilders.standaloneSetup(new TwitchController(service, subscriptions, dashboard, chatCollection, chatDashboard, chatHighlights, clips, clipMedia, clipTranscriptionService, clipMomentsService, interpretationService, null, musicJobService, spokenLyricsService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void spokenPreviewReturnsBoundedWavWithNoStoreAndNeverReflectsProviderText() throws Exception {
        byte[] wav = new byte[]{'R','I','F','F',0,0,0,0,'W','A','V','E'};
        spokenLyricsService.response=wav;
        mockMvc.perform(post("/api/twitch/clips/clip-1/lyrics/lyrics-1/spoken-preview"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "audio/wav"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(wav));
        spokenLyricsService.failure=new SpokenLyricsFailure(409,"spoken_preview_lyrics_stale");
        mockMvc.perform(post("/api/twitch/clips/clip-1/lyrics/lyrics-1/spoken-preview"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("spoken_preview_lyrics_stale"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(not(containsString("raw lyric content"))));
    }

    @Test
    void createAndRetryMusicRoutesReturnAcceptedContracts() throws Exception {
        MusicJobResponse pending = musicJob("local-job", "PENDING", null, false, null, 1, null);
        ((StubMusicJobService) musicJobService).createResponse = pending;
        mockMvc.perform(post("/api/twitch/clips/clip-1/music-jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lyricsId\":\"lyrics-1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("local-job"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        MusicJobResponse retrying = musicJob("local-job", "PENDING", null, false, null, 2, null);
        ((StubMusicJobService) musicJobService).retryResponse = retrying;
        mockMvc.perform(post("/api/twitch/music-jobs/local-job/retry").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAttempt\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attempt").value(2));
        org.junit.jupiter.api.Assertions.assertEquals("clip-1", ((StubMusicJobService) musicJobService).createdClipId);
        org.junit.jupiter.api.Assertions.assertEquals("lyrics-1", ((StubMusicJobService) musicJobService).createdLyricsId);
        org.junit.jupiter.api.Assertions.assertEquals(1, ((StubMusicJobService) musicJobService).retriedAttempt);
    }

    @Test
    void musicStatusAndAudioRoutesPreserveOwnerFailuresAndServeByteRanges() throws Exception {
        mockMvc.perform(get("/api/twitch/music-jobs/foreign-job"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("music_job_not_found"));
        mockMvc.perform(get("/api/twitch/music-jobs/foreign-job/audio"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("music_job_not_found"));

        byte[] audio = "0123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ((StubMusicJobService) musicJobService).audio.put("owned-job", audio);
        mockMvc.perform(get("/api/twitch/music-jobs/owned-job/audio"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Accept-Ranges", "bytes"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(audio));
        mockMvc.perform(get("/api/twitch/music-jobs/owned-job/audio").header("Range", "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Range", "bytes 2-5/10"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes("2345".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        mockMvc.perform(get("/api/twitch/music-jobs/owned-job/audio").header("Range", "bytes=20-30"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Range", "bytes */10"));
    }

    private MusicJobResponse musicJob(String id, String status, String reason, boolean retryable, String retryMode,
            int attempt, String audioPath) {
        java.time.Instant now = java.time.Instant.parse("2026-10-30T10:00:00Z");
        return new MusicJobResponse(id, status, "Test song", "pop", "playful", "medium", java.util.List.of("moment-1"),
                null, retryable, retryMode, attempt, reason, now, now.plusSeconds(900), audioPath);
    }

    private static final class StubMusicJobService extends MusicJobService {
        private MusicJobResponse createResponse, retryResponse;
        private String createdClipId, createdLyricsId;
        private Integer retriedAttempt;
        private final java.util.Map<String, byte[]> audio = new java.util.HashMap<>();
        private StubMusicJobService() { super(null, null, null); }
        @Override public MusicJobResponse create(String clipId, String lyricsId) {
            createdClipId=clipId;createdLyricsId=lyricsId;return createResponse;
        }
        @Override public MusicJobResponse retry(String jobId, int expectedAttempt) {
            retriedAttempt=expectedAttempt;return retryResponse;
        }
        @Override public MusicJobResponse get(String jobId) {
            if (!"owned-job".equals(jobId)) throw new MusicGenerationFailure(404,"music_job_not_found",false);
            return null;
        }
        @Override public byte[] audio(String jobId) {
            byte[] result=audio.get(jobId);if(result==null)throw new MusicGenerationFailure(404,"music_job_not_found",false);return result;
        }
    }

    private static final class StubSpokenLyricsService extends SpokenLyricsService {
        private byte[] response; private SpokenLyricsFailure failure; private String clipId, lyricsId;
        private StubSpokenLyricsService(){super(null,null,null);}
        @Override public byte[] preview(String clipId,String lyricsId){this.clipId=clipId;this.lyricsId=lyricsId;if(failure!=null)throw failure;return response;}
    }

    @Test
    void exposesSingleClipTranscriptionRouteWithTimedSegments() throws Exception {
        ((StubTranscriptionService) clipTranscriptionService).response = new ClipTranscriptionResponse("clip-1", "hello",
                java.util.List.of(new ClipTranscriptionResponse.TimedTranscriptSegment(0.25, 0.9, "hello")));
        mockMvc.perform(post("/api/twitch/clips/clip-1/transcription"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clipId").value("clip-1"))
                .andExpect(jsonPath("$.text").value("hello"))
                .andExpect(jsonPath("$.segments[0].startSeconds").value(0.25))
                .andExpect(jsonPath("$.segments[0].endSeconds").value(0.9));
    }

    @Test
    void exposesSingleClipMemorableMomentsRoute() throws Exception {
        ((StubMomentsService) clipMomentsService).response = new MemorableMomentsResponse("analysis-1", "clip-1",
                new MemorableMomentsResponse.SourceClip("Moment", "creator", 20, "vod-1", 100),
                "verified_target_session", "unaligned", null, "No verified chat timeline.", true,
                "0–100 deterministic evidence ranking, not probability", null, java.util.List.of());
        mockMvc.perform(post("/api/twitch/clips/clip-1/moments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clipId").value("clip-1"))
                .andExpect(jsonPath("$.heuristicOnly").value(true))
                .andExpect(jsonPath("$.chatCorrelation").value("unaligned"));
    }

    @Test
    void interpretationRouteAcceptsOnlyAnalysisIdAndReturnsUnverifiedInterpretation() throws Exception {
        interpretationService.response = new MomentInterpretationResponse("analysis-1", "clip-1", true, true,
                java.util.List.of(new MomentInterpretationResponse.InterpretedMoment("moment-1", "An excited reaction.",
                        "Excited", "High excitement", "Potentially significant", 1.0, 2.0,
                        "verified_target_session", java.util.List.of(new MomentInterpretationResponse.EvidenceCitation(
                                "ref-1", "transcript", "Wow!", 1.0, 2.0)))));
        mockMvc.perform(post("/api/twitch/clips/clip-1/moments/interpretation")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"analysisId\":\"analysis-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisId").value("analysis-1"))
                .andExpect(jsonPath("$.aiGenerated").value(true))
                .andExpect(jsonPath("$.moments[0].startSeconds").value(1.0))
                .andExpect(jsonPath("$.moments[0].evidence[0].id").value("ref-1"));
    }

    @Test
    void connectRedirectsToTwitchAndCallbackReturnsOnlyIdentity() throws Exception {
        MvcResult connectResult = mockMvc.perform(get("/api/twitch/connect"))
                .andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Location", containsString("https://id.twitch.tv/oauth2/authorize")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Location", containsString("response_type=code")))
                .andReturn();
        MockHttpSession session = (MockHttpSession) connectResult.getRequest().getSession(false);
        String location = connectResult.getResponse().getHeader("Location");
        String state = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");

        twitchServer.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"access_token":"hidden-access-token","refresh_token":"hidden-refresh-token","expires_in":3600,"token_type":"bearer"}
                        """, MediaType.APPLICATION_JSON));
        twitchServer.expect(requestTo("https://id.twitch.tv/oauth2/validate"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "OAuth hidden-access-token"))
                .andRespond(withSuccess("""
                        {"client_id":"client-id","user_id":"broadcaster-7","login":"streamer_login","scopes":["user:read:chat","user:bot","channel:bot","channel:manage:clips"],"expires_in":3600}
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/twitch/oauth/callback")
                        .session(session)
                        .param("state", state)
                        .param("code", "one-time-code"))
                .andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Location", "http://localhost:4200?twitch=connected"));

        mockMvc.perform(get("/api/twitch/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.broadcasterId").value("broadcaster-7"))
                .andExpect(jsonPath("$.login").value("streamer_login"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString("hidden-access-token"))));

        mockMvc.perform(get("/api/twitch/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.online").value(false))
                .andExpect(jsonPath("$['subscriptionStatuses']['stream.online']").value("not_configured"));
        mockMvc.perform(post("/api/twitch/session/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.online").value(false))
                .andExpect(jsonPath("$['subscriptionStatuses']['stream.online']").value("not_configured"));
        twitchServer.verify();
    }

    @Test
    void callbackRejectsAStateFromAnotherBrowserSession() throws Exception {
        MvcResult connectResult = mockMvc.perform(get("/api/twitch/connect")).andExpect(status().isFound()).andReturn();
        String location = connectResult.getResponse().getHeader("Location");
        String state = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");

        mockMvc.perform(get("/api/twitch/oauth/callback").param("state", state).param("code", "code"))
                .andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Location", containsString("reason=invalid_state")));
        twitchServer.verify();
    }

    @Test
    void exposesSafeChatSummaryAndLimitedMessageRoute() throws Exception {
        mockMvc.perform(get("/api/twitch/chat/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collecting").value(false))
                .andExpect(jsonPath("$.collectionStatus").value("disconnected"))
                .andExpect(jsonPath("$.totalMessages").value(0));
        mockMvc.perform(get("/api/twitch/chat/messages").param("limit", "999"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
        mockMvc.perform(get("/api/twitch/chat/highlights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heuristicOnly").value(true))
                .andExpect(jsonPath("$.messagesAnalyzed").value(0))
                .andExpect(jsonPath("$.message").value("No timestamped chat messages are available for this stream."))
                .andExpect(jsonPath("$.highlights").isArray());
        mockMvc.perform(get("/api/twitch/clips").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/twitch/clips"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("twitch_account_not_connected"));
        twitchServer.verify();
    }

    @Test
    void validatesChatHighlightQueryParameters() throws Exception {
        mockMvc.perform(get("/api/twitch/chat/highlights").param("windowSeconds", "4"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/twitch/chat/highlights").param("windowSeconds", "301"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/twitch/chat/highlights").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/twitch/chat/highlights").param("limit", "21"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/twitch/chat/highlights").param("windowSeconds", "nope"))
                .andExpect(status().isBadRequest());
        twitchServer.verify();
    }

    @Test
    void accountRemainsConnectedWhenEventSubConflictsWithExistingSubscriptions() throws Exception {
        RestTemplate configuredRestTemplate = new RestTemplate();
        MockRestServiceServer configuredTwitch = MockRestServiceServer.bindTo(configuredRestTemplate).build();
        TwitchOAuthProperties oauthProperties = new TwitchOAuthProperties("client-id", "client-secret",
                "http://localhost:8080/api/twitch/oauth/callback", "http://localhost:4200");
        TwitchEventSubSubscriptionRegistry registry = new TwitchEventSubSubscriptionRegistry();
        TwitchStreamSessionService sessions = new TwitchStreamSessionService();
        com.stream2song.backend.service.TwitchAppAccessTokenService appTokens = new com.stream2song.backend.service.TwitchAppAccessTokenService(oauthProperties, configuredRestTemplate);
        TwitchEventSubSubscriptionService subscriptions = new TwitchEventSubSubscriptionService(oauthProperties,
                new TwitchEventSubProperties("https://tunnel.example/api/twitch/eventsub/webhook", "test-signing-secret-123"),
                registry, appTokens, configuredRestTemplate);
        TwitchOAuthService oauth = new TwitchOAuthService(oauthProperties, configuredRestTemplate);
        TwitchChatMessageService chatMessages = new TwitchChatMessageService();
        TwitchChatCollectionService chatCollection = new TwitchChatCollectionService(subscriptions, sessions, oauth);
        TwitchDashboardService dashboard = new TwitchDashboardService(oauth, sessions, registry);
        TwitchChatDashboardService chatDashboard = new TwitchChatDashboardService(oauth, sessions, registry, chatMessages);
        TwitchChatHighlightService highlights = new TwitchChatHighlightService(oauth, sessions, chatMessages);
        TwitchClipDiscoveryService clips = new TwitchClipDiscoveryService(oauth,
                new TwitchClipsApiClient(configuredRestTemplate, oauthProperties, appTokens),
                new TwitchVideosApiClient(configuredRestTemplate, oauthProperties, appTokens), sessions);
        TwitchClipMediaService clipMedia = new TwitchClipMediaService(oauth, new TwitchClipDownloadsApiClient(configuredRestTemplate, oauthProperties, oauth));
        MockMvc configuredMvc = MockMvcBuilders.standaloneSetup(new TwitchController(oauth, subscriptions,
                dashboard, chatCollection, chatDashboard, highlights, clips, clipMedia,
                new StubTranscriptionService(), new StubMomentsService())).build();

        MvcResult connect = configuredMvc.perform(get("/api/twitch/connect"))
                .andExpect(status().isFound()).andReturn();
        MockHttpSession browserSession = (MockHttpSession) connect.getRequest().getSession(false);
        String state = UriComponentsBuilder.fromUriString(connect.getResponse().getHeader("Location"))
                .build().getQueryParams().getFirst("state");

        configuredTwitch.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andRespond(withSuccess("""
                        {"access_token":"user-access-token","expires_in":3600,"token_type":"bearer"}
                        """, MediaType.APPLICATION_JSON));
        configuredTwitch.expect(requestTo("https://id.twitch.tv/oauth2/validate"))
                .andRespond(withSuccess("""
                        {"client_id":"client-id","user_id":"broadcaster-7","login":"streamer_login",
                         "scopes":["user:read:chat","user:bot","channel:bot","channel:manage:clips"],"expires_in":3600}
                        """, MediaType.APPLICATION_JSON));
        configuredTwitch.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andRespond(withSuccess("""
                        {"access_token":"app-access-token","expires_in":3600,"token_type":"bearer"}
                        """, MediaType.APPLICATION_JSON));
        expectEventSubConflictAndExisting(configuredTwitch, "stream.online", "enabled", "online-id");
        expectEventSubConflictAndExisting(configuredTwitch, "stream.offline",
                "webhook_callback_verification_pending", "offline-id");
        configuredTwitch.expect(requestTo("https://api.twitch.tv/helix/streams?user_id=broadcaster-7"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        configuredMvc.perform(get("/api/twitch/oauth/callback").session(browserSession)
                        .param("state", state).param("code", "authorization-code"))
                .andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Location", "http://localhost:4200?twitch=connected"));
        configuredMvc.perform(get("/api/twitch/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.login").value("streamer_login"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString("user-access-token"))));
        configuredMvc.perform(get("/api/twitch/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.subscriptionStatuses['stream.online']").value("enabled"))
                .andExpect(jsonPath("$.subscriptionStatuses['stream.offline']")
                        .value("webhook_callback_verification_pending"));
        configuredTwitch.verify();
    }

    private void expectEventSubConflictAndExisting(MockRestServiceServer server, String eventType,
            String subscriptionStatus, String subscriptionId) {
        server.expect(requestTo("https://api.twitch.tv/helix/eventsub/subscriptions"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Conflict\",\"status\":409,\"id\":\"existing\"}"));
        server.expect(requestTo("https://api.twitch.tv/helix/eventsub/subscriptions?type=" + eventType))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer app-access-token"))
                .andRespond(withSuccess("""
                        {"data":[{"id":"%s","status":"%s","type":"%s","version":"1",
                         "condition":{"broadcaster_user_id":"broadcaster-7"},
                         "transport":{"method":"webhook","callback":"https://tunnel.example/api/twitch/eventsub/webhook"}}],"pagination":{}}
                        """.formatted(subscriptionId, subscriptionStatus, eventType), MediaType.APPLICATION_JSON));
    }

    private static final class StubTranscriptionService extends ClipTranscriptionService {
        private ClipTranscriptionResponse response;
        private StubTranscriptionService() { super(null, null, null); }
        @Override public ClipTranscriptionResponse transcribe(String clipId) { return response; }
        @Override public ClipTranscriptionResponse transcribe(String clipId, boolean refresh,
                java.util.function.Consumer<String> onRefreshSuccess) {
            if (refresh) onRefreshSuccess.accept("broadcaster-7");
            return response;
        }
    }

    private static final class StubMomentsService extends TwitchClipMomentsService {
        private MemorableMomentsResponse response;
        private StubMomentsService() { super(null, null, null, null, null, null, null, null); }
        @Override public MemorableMomentsResponse extract(String clipId) { return response; }
    }

    private static final class StubInterpretationService extends MomentInterpretationService {
        private MomentInterpretationResponse response;
        private StubInterpretationService() { super(null, null, null); }
        @Override public MomentInterpretationResponse interpret(String clipId, String analysisId) { return response; }
    }
}
