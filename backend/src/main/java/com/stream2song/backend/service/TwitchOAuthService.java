package com.stream2song.backend.service;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.stream2song.backend.config.TwitchOAuthProperties;
import com.stream2song.backend.dto.TwitchConnectionStatusResponse;
import com.stream2song.backend.dto.TwitchTokenResponse;
import com.stream2song.backend.dto.TwitchTokenValidationResponse;

@Service
public class TwitchOAuthService implements TwitchBroadcasterProvider {
    private static final URI AUTHORIZE_URI = URI.create("https://id.twitch.tv/oauth2/authorize");
    private static final URI TOKEN_URI = URI.create("https://id.twitch.tv/oauth2/token");
    private static final URI VALIDATE_URI = URI.create("https://id.twitch.tv/oauth2/validate");
    private static final Duration STATE_LIFETIME = Duration.ofMinutes(10);
    private static final String REQUIRED_SCOPES = "user:read:chat user:bot channel:bot channel:manage:clips";

    private final TwitchOAuthProperties properties;
    private final RestTemplate restTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, PendingOAuthState> pendingStates = new ConcurrentHashMap<>();
    private final AtomicReference<ConnectedAccount> connectedAccount = new AtomicReference<>();
    private final AtomicLong connectionGenerations = new AtomicLong();

    public TwitchOAuthService(TwitchOAuthProperties properties, RestTemplate twitchRestTemplate) {
        this.properties = properties;
        this.restTemplate = twitchRestTemplate;
    }

    public URI createAuthorizationUri(String browserSessionId) {
        requireConfiguration();
        Instant now = Instant.now();
        pendingStates.entrySet().removeIf(entry -> entry.getValue().createdAt().plus(STATE_LIFETIME).isBefore(now));

        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        pendingStates.put(state, new PendingOAuthState(now, browserSessionId));

        return UriComponentsBuilder.fromUri(AUTHORIZE_URI)
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", REQUIRED_SCOPES)
                .queryParam("state", state)
                .build()
                .encode()
                .toUri();
    }

    public void completeAuthorization(String state, String code, String authorizationError, String browserSessionId) {
        consumeState(state, browserSessionId);

        if (authorizationError != null && !authorizationError.isBlank()) {
            throw new TwitchOAuthFailure("access_denied".equals(authorizationError)
                    ? "authorization_denied" : "authorization_failed");
        }
        if (code == null || code.isBlank()) {
            throw new TwitchOAuthFailure("authorization_code_missing");
        }
        requireConfiguration();

        TwitchTokenResponse token = exchangeCode(code);
        TwitchTokenValidationResponse identity = validateToken(token.accessToken());
        if (!properties.clientId().equals(identity.clientId())
                || !hasText(identity.userId()) || !hasText(identity.login())) {
            throw new TwitchOAuthFailure("token_validation_failed");
        }
        if (identity.scopes() == null || !identity.scopes().containsAll(
                java.util.List.of("user:read:chat", "user:bot", "channel:bot"))) {
            throw new TwitchOAuthFailure("chat_scopes_missing");
        }
        connectedAccount.set(new ConnectedAccount(identity.userId(), identity.login(), token.accessToken(),
                identity.scopes().contains("channel:manage:clips"), connectionGenerations.incrementAndGet()));
    }

    public TwitchConnectionStatusResponse connectionStatus() {
        ConnectedAccount account = connectedAccount.get();
        if (account == null) {
            return TwitchConnectionStatusResponse.disconnected();
        }
        return TwitchConnectionStatusResponse.connected(account.broadcasterId(), account.login());
    }

    @Override
    public String connectedBroadcasterId() {
        ConnectedAccount account = connectedAccount.get();
        return account == null ? null : account.broadcasterId();
    }

    @Override
    public ConnectionScope connectionScope() {
        ConnectedAccount account = connectedAccount.get();
        return account == null ? null : new ConnectionScope(account.broadcasterId(), account.generation());
    }

    public ClipAuthorizationContext clipAuthorizationContext(ConnectionScope expectedScope) {
        ConnectedAccount account = connectedAccount.get();
        if (account == null || expectedScope == null || !account.broadcasterId().equals(expectedScope.broadcasterId())
                || account.generation() != expectedScope.generation()) return null;
        return new ClipAuthorizationContext(expectedScope, account.accessToken(), account.clipDownloadScope());
    }

    public boolean isCurrentConnection(ConnectionScope expectedScope) {
        return expectedScope != null && expectedScope.equals(connectionScope());
    }

    public record ClipAuthorizationContext(ConnectionScope scope, String accessToken, boolean clipDownloadScope) {
        @Override public String toString() { return "ClipAuthorizationContext[scope=" + scope + ", accessToken=redacted]"; }
    }

    public String connectedUserAccessToken() {
        ConnectedAccount account = connectedAccount.get();
        return account == null ? null : account.accessToken();
    }

    public boolean hasClipDownloadScope() {
        ConnectedAccount account = connectedAccount.get();
        return account != null && account.clipDownloadScope();
    }

    public URI frontendRedirect(String outcome, String reason) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.frontendBaseUrl())
                .replaceQuery(null)
                .fragment(null)
                .queryParam("twitch", outcome);
        if (reason != null) {
            builder.queryParam("reason", reason);
        }
        return builder.build().encode().toUri();
    }

    private TwitchTokenResponse exchangeCode(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", code);
        form.add("grant_type", "authorization_code");
        form.add("redirect_uri", properties.redirectUri());

        try {
            ResponseEntity<TwitchTokenResponse> response = restTemplate.postForEntity(
                    TOKEN_URI, new HttpEntity<>(form, headers), TwitchTokenResponse.class);
            TwitchTokenResponse token = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || token == null || !hasText(token.accessToken())) {
                throw new TwitchOAuthFailure("token_exchange_failed");
            }
            return token;
        } catch (RestClientException exception) {
            throw new TwitchOAuthFailure("token_exchange_failed");
        }
    }

    private TwitchTokenValidationResponse validateToken(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "OAuth " + accessToken);
        try {
            ResponseEntity<TwitchTokenValidationResponse> response = restTemplate.exchange(
                    VALIDATE_URI, org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers), TwitchTokenValidationResponse.class);
            TwitchTokenValidationResponse identity = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || identity == null) {
                throw new TwitchOAuthFailure("token_validation_failed");
            }
            return identity;
        } catch (RestClientException exception) {
            throw new TwitchOAuthFailure("token_validation_failed");
        }
    }

    private void consumeState(String state, String browserSessionId) {
        if (!hasText(state)) {
            throw new TwitchOAuthFailure("invalid_state");
        }
        PendingOAuthState pendingState = pendingStates.get(state);
        if (pendingState == null || !pendingState.browserSessionId().equals(browserSessionId)) {
            throw new TwitchOAuthFailure("invalid_state");
        }
        if (!pendingStates.remove(state, pendingState)
                || pendingState.createdAt().plus(STATE_LIFETIME).isBefore(Instant.now())) {
            throw new TwitchOAuthFailure("invalid_state");
        }
    }

    private void requireConfiguration() {
        if (!properties.isConfigured()) {
            throw new TwitchOAuthFailure("configuration_missing");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record PendingOAuthState(Instant createdAt, String browserSessionId) { }

    /** Deliberately not a record: generated toString methods must never include the access token. */
    private static final class ConnectedAccount {
        private final String broadcasterId;
        private final String login;
        @SuppressWarnings("unused")
        private final String accessToken;
        private final boolean clipDownloadScope;
        private final long generation;

        private ConnectedAccount(String broadcasterId, String login, String accessToken, boolean clipDownloadScope,
                long generation) {
            this.broadcasterId = broadcasterId;
            this.login = login;
            this.accessToken = accessToken;
            this.clipDownloadScope = clipDownloadScope;
            this.generation = generation;
        }

        private String broadcasterId() { return broadcasterId; }
        private String login() { return login; }
        private String accessToken() { return accessToken; }
        private boolean clipDownloadScope() { return clipDownloadScope; }
        private long generation() { return generation; }
    }
}
