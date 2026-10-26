package com.stream2song.backend.service;

import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.stream2song.backend.config.TwitchOAuthProperties;
import com.stream2song.backend.dto.TwitchConnectionStatusResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

class TwitchOAuthServiceTest {
    private static final String SESSION_ID = "test-browser-session";
    private static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String REDIRECT_URI = "http://localhost:8080/api/twitch/oauth/callback";

    private RestTemplate restTemplate;
    private MockRestServiceServer twitchServer;
    private TwitchOAuthService service;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        twitchServer = MockRestServiceServer.bindTo(restTemplate).build();
        service = new TwitchOAuthService(
                new TwitchOAuthProperties(CLIENT_ID, CLIENT_SECRET, REDIRECT_URI, "http://localhost:4200"),
                restTemplate);
    }

    @Test
    void exchangesAndValidatesTokenThenExposesOnlyBroadcasterIdentity() {
        expectTokenExchange();
        twitchServer.expect(requestTo("https://id.twitch.tv/oauth2/validate"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "OAuth test-access-token"))
                .andRespond(withSuccess("""
                        {"client_id":"test-client-id","user_id":"12345","login":"streamer_one","scopes":["user:read:chat","user:bot","channel:bot"],"expires_in":3600}
                        """, MediaType.APPLICATION_JSON));

        String state = stateFrom(service.createAuthorizationUri(SESSION_ID));
        assertThat(UriComponentsBuilder.fromUri(service.createAuthorizationUri(SESSION_ID)).build()
                .getQueryParams().getFirst("scope")).isEqualTo("user:read:chat%20user:bot%20channel:bot");
        service.completeAuthorization(state, "one-time-code", null, SESSION_ID);

        TwitchConnectionStatusResponse status = service.connectionStatus();
        assertThat(status.connected()).isTrue();
        assertThat(status.broadcasterId()).isEqualTo("12345");
        assertThat(status.login()).isEqualTo("streamer_one");
        assertThat(status.toString()).doesNotContain("test-access-token", "test-refresh-token", CLIENT_SECRET);
        twitchServer.verify();
    }

    @Test
    void rejectsValidatedTokenWithoutRequiredChatScopes() {
        expectTokenExchange();
        twitchServer.expect(requestTo("https://id.twitch.tv/oauth2/validate"))
                .andRespond(withSuccess("""
                        {"client_id":"test-client-id","user_id":"12345","login":"streamer_one","scopes":[],"expires_in":3600}
                        """, MediaType.APPLICATION_JSON));
        String state = stateFrom(service.createAuthorizationUri(SESSION_ID));

        assertThatThrownBy(() -> service.completeAuthorization(state, "one-time-code", null, SESSION_ID))
                .isInstanceOf(TwitchOAuthFailure.class).hasMessage("chat_scopes_missing");
        assertThat(service.connectionStatus().connected()).isFalse();
        twitchServer.verify();
    }

    @Test
    void rejectsMissingOrMismatchedStateWithoutCallingTwitch() {
        service.createAuthorizationUri(SESSION_ID);

        assertThatThrownBy(() -> service.completeAuthorization("wrong-state", "code", null, SESSION_ID))
                .isInstanceOf(TwitchOAuthFailure.class)
                .hasMessage("invalid_state");
        assertThatThrownBy(() -> service.completeAuthorization(null, "code", null, SESSION_ID))
                .isInstanceOf(TwitchOAuthFailure.class)
                .hasMessage("invalid_state");
        twitchServer.verify();
    }

    @Test
    void rejectsReusedStateWithoutCallingTwitch() {
        String state = stateFrom(service.createAuthorizationUri(SESSION_ID));
        assertThatThrownBy(() -> service.completeAuthorization(state, null, "access_denied", SESSION_ID))
                .isInstanceOf(TwitchOAuthFailure.class)
                .hasMessage("authorization_denied");

        assertThatThrownBy(() -> service.completeAuthorization(state, "code", null, SESSION_ID))
                .isInstanceOf(TwitchOAuthFailure.class)
                .hasMessage("invalid_state");
        twitchServer.verify();
    }

    @Test
    void handlesAuthorizationDenialAfterConsumingValidState() {
        String state = stateFrom(service.createAuthorizationUri(SESSION_ID));

        assertThatThrownBy(() -> service.completeAuthorization(state, null, "access_denied", SESSION_ID))
                .isInstanceOf(TwitchOAuthFailure.class)
                .hasMessage("authorization_denied");
        assertThat(service.connectionStatus().connected()).isFalse();
        twitchServer.verify();
    }

    @Test
    void rejectsTokenWhenTwitchValidationFails() {
        expectTokenExchange();
        twitchServer.expect(requestTo("https://id.twitch.tv/oauth2/validate"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(UNAUTHORIZED));
        String state = stateFrom(service.createAuthorizationUri(SESSION_ID));

        assertThatThrownBy(() -> service.completeAuthorization(state, "one-time-code", null, SESSION_ID))
                .isInstanceOf(TwitchOAuthFailure.class)
                .hasMessage("token_validation_failed");
        assertThat(service.connectionStatus().connected()).isFalse();
        twitchServer.verify();
    }

    @Test
    void failsAuthorizationWhenConfigurationIsMissing() {
        TwitchOAuthService unconfigured = new TwitchOAuthService(
                new TwitchOAuthProperties("", "", "", "http://localhost:4200"), restTemplate);

        assertThatThrownBy(() -> unconfigured.createAuthorizationUri(SESSION_ID))
                .isInstanceOf(TwitchOAuthFailure.class)
                .hasMessage("configuration_missing");
        twitchServer.verify();
    }

    private void expectTokenExchange() {
        twitchServer.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("client_id=" + CLIENT_ID),
                        org.hamcrest.Matchers.containsString("client_secret=" + CLIENT_SECRET),
                        org.hamcrest.Matchers.containsString("code=one-time-code"),
                        org.hamcrest.Matchers.containsString("grant_type=authorization_code"),
                        org.hamcrest.Matchers.containsString("redirect_uri="))))
                .andRespond(withSuccess("""
                        {"access_token":"test-access-token","refresh_token":"test-refresh-token","expires_in":3600,"token_type":"bearer"}
                        """, MediaType.APPLICATION_JSON));
    }

    private String stateFrom(URI authorizationUri) {
        return UriComponentsBuilder.fromUri(authorizationUri).build().getQueryParams().getFirst("state");
    }
}
