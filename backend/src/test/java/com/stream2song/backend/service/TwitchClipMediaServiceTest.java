package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.stream2song.backend.client.TwitchClipDownloadsApiClient;
import com.stream2song.backend.config.TwitchOAuthProperties;

class TwitchClipMediaServiceTest {
    @Test
    void returnsTwitchMediaUrlsWithConnectedBroadcasterUserToken() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        var properties = new TwitchOAuthProperties("client", "secret", "http://localhost/callback", "http://localhost:4200");
        TwitchOAuthService oauth = new TwitchOAuthService(properties, rest);
        server.expect(requestTo("https://api.twitch.tv/helix/clips/downloads?editor_id=broadcaster-1&broadcaster_id=broadcaster-1&clip_id=clip-1"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Client-Id", "client"))
                .andExpect(header("Authorization", "Bearer secret-user-token"))
                .andRespond(withSuccess("""
                        {"data":[{"clip_id":"clip-1","landscape_download_url":"https://media.example/clip.mp4","portrait_download_url":null}]}
                        """, MediaType.APPLICATION_JSON));
        authorize(oauth, server);
        var service = new TwitchClipMediaService(oauth,
                new TwitchClipDownloadsApiClient(rest, properties, oauth));
        var media = service.downloadUrls("clip-1");
        assertEquals("clip-1", media.clipId());
        assertEquals("https://media.example/clip.mp4", media.landscapeDownloadUrl());
        assertNull(media.portraitDownloadUrl());
        server.verify();
    }

    @Test
    void rejectsClipIdsBeforeMakingAnApiCall() {
        var service = new TwitchClipMediaService(() -> "broadcaster-1", null);
        var failure = assertThrows(TwitchClipMediaService.ClipMediaFailure.class,
                () -> service.downloadUrls("https://example.com/clip"));
        assertEquals("invalid_clip_id", failure.reason());
    }

    private static void authorize(TwitchOAuthService oauth, MockRestServiceServer server) {
        var state = org.springframework.web.util.UriComponentsBuilder.fromUri(oauth.createAuthorizationUri("session"))
                .build().getQueryParams().getFirst("state");
        server.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"secret-user-token\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://id.twitch.tv/oauth2/validate"))
                .andRespond(withSuccess("""
                        {"client_id":"client","user_id":"broadcaster-1","login":"viewer","scopes":["user:read:chat","user:bot","channel:bot","channel:manage:clips"],"expires_in":3600}
                        """, MediaType.APPLICATION_JSON));
        oauth.completeAuthorization(state, "code", null, "session");
    }
}
