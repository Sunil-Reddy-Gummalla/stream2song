package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.stream2song.backend.client.TwitchClipsApiClient;
import com.stream2song.backend.client.TwitchVideosApiClient;
import com.stream2song.backend.config.TwitchOAuthProperties;

class TwitchClipDiscoveryServiceTest {
    private MockRestServiceServer twitch;
    private TwitchClipDiscoveryService service;
    private TwitchStreamSessionService sessions;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        twitch = MockRestServiceServer.bindTo(restTemplate).build();
        TwitchOAuthProperties properties = new TwitchOAuthProperties("test-client", "test-secret",
                "http://localhost:8080/api/twitch/oauth/callback", "http://localhost:4200");
        TwitchAppAccessTokenService appToken = new TwitchAppAccessTokenService(properties, restTemplate);
        sessions = new TwitchStreamSessionService();
        service = new TwitchClipDiscoveryService(() -> "broadcaster-1",
                new TwitchClipsApiClient(restTemplate, properties, appToken),
                new TwitchVideosApiClient(restTemplate, properties, appToken), sessions);
    }

    @Test
    void verifiesClipAssociationByResolvingVodToTargetStreamId() {
        sessions.recordOnline("broadcaster-1", "stream-42", Instant.parse("2026-10-25T11:00:00Z"));
        expectAppToken();
        twitch.expect(requestTo(org.hamcrest.Matchers.containsString("https://api.twitch.tv/helix/clips?broadcaster_id=broadcaster-1&first=20&started_at=")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Client-Id", "test-client"))
                .andExpect(header("Authorization", "Bearer app-token"))
                .andRespond(withSuccess("""
                        {"data":[{"id":"clip-1","title":"Great moment","creator_name":"creator",
                         "created_at":"2026-10-25T12:00:00Z","duration":31.2,"url":"https://clips.twitch.tv/clip-1",
                         "thumbnail_url":"https://thumb.example/clip.jpg","video_id":"vod-9","vod_offset":780}],
                         "pagination":{"cursor":"cursor-1"}}
                        """, MediaType.APPLICATION_JSON));
        twitch.expect(requestTo("https://api.twitch.tv/helix/videos?id=vod-9"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer app-token"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"vod-9\",\"stream_id\":\"stream-42\"}]}",
                        MediaType.APPLICATION_JSON));

        var response = service.discover(null, null, null, null);

        assertEquals("recent_broadcaster_clips", response.association());
        assertTrue(response.associationNote().contains("stream ID"));
        assertEquals("stream-42", response.targetSessionStreamId());
        assertEquals("cursor-1", response.nextCursor());
        assertEquals("clip-1", response.clips().getFirst().id());
        assertEquals("vod-9", response.clips().getFirst().vodId());
        assertEquals(780, response.clips().getFirst().vodOffset());
        assertEquals("verified_target_session", response.clips().getFirst().sessionAssociation());
        twitch.verify();
    }

    @Test
    void unmatchedVodStreamDoesNotAssociateClipWithTargetSession() {
        sessions.recordOnline("broadcaster-1", "target-stream", Instant.parse("2026-10-25T11:00:00Z"));
        expectAppToken();
        twitch.expect(requestTo(org.hamcrest.Matchers.containsString("/helix/clips?")))
                .andRespond(withSuccess("""
                        {"data":[{"id":"clip-other","title":"Other","creator_name":"creator",
                         "created_at":"2026-10-25T12:00:00Z","duration":10,"url":"https://clips.twitch.tv/other",
                         "thumbnail_url":"https://thumb.example/other.jpg","video_id":"vod-other","vod_offset":20}]}
                        """, MediaType.APPLICATION_JSON));
        twitch.expect(requestTo("https://api.twitch.tv/helix/videos?id=vod-other"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"vod-other\",\"stream_id\":\"another-stream\"}]}",
                        MediaType.APPLICATION_JSON));
        var response = service.discover(5, null, null, null);
        assertEquals("different_session", response.clips().getFirst().sessionAssociation());
        twitch.verify();
    }

    @Test
    void missingVodMetadataLeavesAssociationUnknown() {
        sessions.recordOnline("broadcaster-1", "target-stream", Instant.parse("2026-10-25T11:00:00Z"));
        expectAppToken();
        twitch.expect(requestTo(org.hamcrest.Matchers.containsString("/helix/clips?")))
                .andRespond(withSuccess("""
                        {"data":[{"id":"clip-no-vod","title":"No VOD","creator_name":"creator",
                         "created_at":"2026-10-25T12:00:00Z","duration":10,"url":"https://clips.twitch.tv/no-vod",
                         "thumbnail_url":"https://thumb.example/no-vod.jpg","video_id":"","vod_offset":null}]}
                        """, MediaType.APPLICATION_JSON));
        var response = service.discover(5, null, null, null);
        assertNull(response.clips().getFirst().vodId());
        assertNull(response.clips().getFirst().vodOffset());
        assertEquals("unknown", response.clips().getFirst().sessionAssociation());
        twitch.verify();
    }

    @Test
    void resultsRemainAvailableAsRecentBroadcasterFallbackWithoutTargetSession() {
        expectAppToken();
        twitch.expect(requestTo(org.hamcrest.Matchers.containsString("/helix/clips?")))
                .andRespond(withSuccess("""
                        {"data":[{"id":"clip-recent","title":"Recent","creator_name":"creator",
                         "created_at":"2026-10-25T12:00:00Z","duration":10,"url":"https://clips.twitch.tv/recent",
                         "thumbnail_url":"https://thumb.example/recent.jpg","video_id":"vod-1","vod_offset":4}]}
                        """, MediaType.APPLICATION_JSON));
        var response = service.discover(5, null, null, null);
        assertEquals("recent_broadcaster_clips", response.association());
        assertEquals("unknown", response.clips().getFirst().sessionAssociation());
        assertEquals("clip-recent", response.clips().getFirst().id());
        twitch.verify();
    }

    @Test
    void emptyResultProducesAnEmptyPage() {
        expectAppToken();
        twitch.expect(requestTo(org.hamcrest.Matchers.containsString("/helix/clips?")))
                .andRespond(withSuccess("{\"data\":[],\"pagination\":{}}", MediaType.APPLICATION_JSON));
        assertTrue(service.discover(1, null, null, null).clips().isEmpty());
        twitch.verify();
    }

    @Test
    void rejectsInvalidLimitAndTimeFiltersBeforeCallingTwitch() {
        assertThrows(IllegalArgumentException.class, () -> service.discover(0, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.discover(101, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.discover(10, null, Instant.now(), null));
        assertThrows(IllegalArgumentException.class, () -> service.discover(10, null,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-10T00:00:00Z")));
        twitch.verify();
    }

    @Test
    void mapsAuthenticationPermissionRateLimitAndUpstreamFailures() {
        expectAppToken();
        twitch.expect(requestTo(org.hamcrest.Matchers.containsString("/helix/clips?")))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));
        twitch.expect(requestTo(org.hamcrest.Matchers.containsString("/helix/clips?")))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));
        twitch.expect(requestTo(org.hamcrest.Matchers.containsString("/helix/clips?")))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        twitch.expect(requestTo(org.hamcrest.Matchers.containsString("/helix/clips?")))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));
        var unauthorized = assertThrows(TwitchClipDiscoveryService.ClipDiscoveryFailure.class,
                () -> service.discover(10, null, null, null));
        assertEquals("twitch_app_token_rejected", unauthorized.reason());

        var forbidden = assertThrows(TwitchClipDiscoveryService.ClipDiscoveryFailure.class,
                () -> service.discover(10, null, null, null));
        assertEquals("twitch_clip_permission_denied", forbidden.reason());

        assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                assertThrows(TwitchClipDiscoveryService.ClipDiscoveryFailure.class,
                        () -> service.discover(10, null, null, null)).status());

        var failed = assertThrows(TwitchClipDiscoveryService.ClipDiscoveryFailure.class,
                () -> service.discover(10, null, null, null));
        assertEquals("twitch_api_request_failed", failed.reason());
        twitch.verify();
    }

    @Test
    void disconnectedBroadcasterDoesNotCallTwitch() {
        var disconnected = new TwitchClipDiscoveryService(() -> null, null, null, sessions);
        var failure = assertThrows(TwitchClipDiscoveryService.ClipDiscoveryFailure.class,
                () -> disconnected.discover(10, null, null, null));
        assertEquals(409, failure.status().value());
        twitch.verify();
    }

    private void expectAppToken() {
        twitch.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=client_credentials")))
                .andRespond(withSuccess("{\"access_token\":\"app-token\",\"expires_in\":3600,\"token_type\":\"bearer\"}",
                        MediaType.APPLICATION_JSON));
    }
}
