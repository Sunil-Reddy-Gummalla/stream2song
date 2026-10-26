package com.stream2song.backend.service;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import com.stream2song.backend.config.TwitchEventSubProperties;
import com.stream2song.backend.config.TwitchOAuthProperties;
import com.stream2song.backend.dto.TwitchAppAccessTokenResponse;
import com.stream2song.backend.dto.TwitchEventSubCreateResponse;
import com.stream2song.backend.dto.TwitchStreamsResponse;

@Service
public class TwitchEventSubSubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(TwitchEventSubSubscriptionService.class);
    private static final URI TOKEN_URI = URI.create("https://id.twitch.tv/oauth2/token");
    private static final URI SUBSCRIPTIONS_URI = URI.create("https://api.twitch.tv/helix/eventsub/subscriptions");
    private static final URI STREAMS_URI = URI.create("https://api.twitch.tv/helix/streams");
    private static final List<String> STREAM_EVENT_TYPES = List.of("stream.online", "stream.offline");
    private static final String CHAT_EVENT_TYPE = "channel.chat.message";

    private final TwitchOAuthProperties oauthProperties;
    private final TwitchEventSubProperties eventSubProperties;
    private final TwitchEventSubSubscriptionRegistry registry;
    private final RestTemplate restTemplate;
    private CachedAppToken cachedAppToken;

    public TwitchEventSubSubscriptionService(
            TwitchOAuthProperties oauthProperties,
            TwitchEventSubProperties eventSubProperties,
            TwitchEventSubSubscriptionRegistry registry,
            RestTemplate twitchRestTemplate) {
        this.oauthProperties = oauthProperties;
        this.eventSubProperties = eventSubProperties;
        this.registry = registry;
        this.restTemplate = twitchRestTemplate;
    }

    public synchronized void subscribeToStreamEvents(String broadcasterId) {
        if (broadcasterId == null || broadcasterId.isBlank()) return;
        if (!eventSubProperties.isConfigured()) {
            log.warn("Skipping Twitch stream EventSub subscriptions: {}", eventSubProperties.configurationProblem());
            STREAM_EVENT_TYPES.forEach(type -> registry.markStatus(broadcasterId, type, "not_configured"));
            return;
        }
        if (!oauthProperties.isConfigured()) {
            log.warn("Skipping Twitch stream EventSub subscriptions: Twitch OAuth client configuration is incomplete");
            STREAM_EVENT_TYPES.forEach(type -> registry.markStatus(broadcasterId, type, "failed"));
            return;
        }

        String appToken;
        try {
            appToken = getAppAccessToken();
        } catch (RestClientResponseException exception) {
            log.warn("Could not obtain Twitch App Access Token for stream subscriptions: HTTP {}",
                    exception.getStatusCode().value());
            STREAM_EVENT_TYPES.forEach(type -> registry.markStatus(broadcasterId, type, "failed"));
            return;
        } catch (RestClientException | IllegalStateException exception) {
            log.warn("Could not obtain Twitch App Access Token for stream subscriptions: {}",
                    safeFailure(exception));
            STREAM_EVENT_TYPES.forEach(type -> registry.markStatus(broadcasterId, type, "failed"));
            return;
        }

        for (String eventType : STREAM_EVENT_TYPES) {
            if (registry.isActive(broadcasterId, eventType)) continue;
            try {
                createSubscription(broadcasterId, eventType, appToken);
            } catch (RestClientResponseException exception) {
                log.warn("Twitch EventSub subscription {} failed: HTTP {}", eventType,
                        exception.getStatusCode().value());
                registry.markStatus(broadcasterId, eventType, "failed");
            } catch (RestClientException | IllegalStateException exception) {
                log.warn("Twitch EventSub subscription {} failed: {}", eventType, safeFailure(exception));
                registry.markStatus(broadcasterId, eventType, "failed");
            }
        }
    }

    public synchronized void subscribeToChatMessages(String broadcasterId, String botUserId) {
        if (!eventSubProperties.isConfigured()) {
            log.warn("Skipping Twitch chat EventSub subscription: {}", eventSubProperties.configurationProblem());
            registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "not_configured");
            return;
        }
        if (!oauthProperties.isConfigured() || broadcasterId == null || botUserId == null) {
            log.warn("Skipping Twitch chat EventSub subscription: Twitch OAuth client configuration or broadcaster identity is missing");
            registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "authorization_required");
            return;
        }
        if (registry.isActive(broadcasterId, CHAT_EVENT_TYPE)) return;
        try {
            createChatSubscription(broadcasterId, botUserId, getAppAccessToken());
        } catch (RestClientResponseException exception) {
            log.warn("Twitch chat EventSub subscription failed: HTTP {}", exception.getStatusCode().value());
            registry.markStatus(broadcasterId, CHAT_EVENT_TYPE,
                    exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403
                            ? "authorization_required" : "failed");
        } catch (RestClientException | IllegalStateException exception) {
            log.warn("Twitch chat EventSub subscription failed: {}", safeFailure(exception));
            registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "failed");
        }
    }

    public synchronized void stopChatMessages(String broadcasterId) {
        String id = registry.subscriptionIdFor(broadcasterId, CHAT_EVENT_TYPE);
        if (id == null) {
            registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "stopped");
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(getAppAccessToken());
            headers.set("Client-Id", oauthProperties.clientId());
            URI uri = org.springframework.web.util.UriComponentsBuilder.fromUri(SUBSCRIPTIONS_URI)
                    .queryParam("id", id).build().toUri();
            restTemplate.exchange(uri, org.springframework.http.HttpMethod.DELETE,
                    new HttpEntity<>(headers), Void.class);
            registry.remove(id);
            registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "stopped");
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                registry.remove(id);
                registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "stopped");
            } else {
                log.warn("Could not stop Twitch chat EventSub subscription: HTTP {}", exception.getStatusCode().value());
                registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "failed");
            }
        } catch (RestClientException | IllegalStateException exception) {
            log.warn("Could not stop Twitch chat EventSub subscription: {}", safeFailure(exception));
            registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "failed");
        }
    }

    public synchronized TwitchStreamsResponse.Stream activeStream(String broadcasterId) {
        if (!eventSubProperties.isConfigured() || !oauthProperties.isConfigured()) {
            registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "not_configured");
            return null;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(getAppAccessToken());
            headers.set("Client-Id", oauthProperties.clientId());
            URI uri = org.springframework.web.util.UriComponentsBuilder.fromUri(STREAMS_URI)
                    .queryParam("user_id", broadcasterId).build().toUri();
            TwitchStreamsResponse response = restTemplate.exchange(uri, org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers), TwitchStreamsResponse.class).getBody();
            if (response == null || response.data() == null) {
                throw new IllegalStateException("Twitch Get Streams returned an incomplete response");
            }
            if (response.data().isEmpty()) {
                registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "awaiting_stream");
                return null;
            }
            return response.data().getFirst();
        } catch (RestClientResponseException exception) {
            log.warn("Twitch Get Streams request failed for the connected broadcaster: HTTP {}",
                    exception.getStatusCode().value());
            registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "failed");
            return null;
        } catch (RestClientException | IllegalStateException exception) {
            log.warn("Twitch Get Streams request failed for the connected broadcaster: {}", safeFailure(exception));
            registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "failed");
            return null;
        }
    }

    private void createChatSubscription(String broadcasterId, String botUserId, String appToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(appToken);
        headers.set("Client-Id", oauthProperties.clientId());
        Map<String, Object> request = Map.of(
                "type", CHAT_EVENT_TYPE,
                "version", "1",
                "condition", Map.of("broadcaster_user_id", broadcasterId, "user_id", botUserId),
                "transport", Map.of("method", "webhook", "callback", eventSubProperties.callbackUrl(),
                        "secret", eventSubProperties.signingSecret()));
        TwitchEventSubCreateResponse response = restTemplate.postForObject(
                SUBSCRIPTIONS_URI, new HttpEntity<>(request, headers), TwitchEventSubCreateResponse.class);
        if (response == null || response.data() == null || response.data().isEmpty())
            throw new IllegalStateException("Twitch did not return a chat subscription");
        TwitchEventSubCreateResponse.Subscription subscription = response.data().getFirst();
        if (subscription.id() == null || subscription.status() == null || !CHAT_EVENT_TYPE.equals(subscription.type()))
            throw new IllegalStateException("Twitch returned an incomplete chat subscription");
        registry.record(subscription.id(), broadcasterId, CHAT_EVENT_TYPE, subscription.status());
    }

    public void markChatStatus(String broadcasterId, String status) {
        registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, status);
    }

    public String chatStatus(String broadcasterId) {
        return registry.statusesFor(broadcasterId).get(CHAT_EVENT_TYPE);
    }

    private synchronized String getAppAccessToken() {
        if (cachedAppToken != null && cachedAppToken.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return cachedAppToken.token();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", oauthProperties.clientId());
        form.add("client_secret", oauthProperties.clientSecret());
        form.add("grant_type", "client_credentials");

        TwitchAppAccessTokenResponse token = restTemplate.postForObject(
                TOKEN_URI, new HttpEntity<>(form, headers), TwitchAppAccessTokenResponse.class);
        if (token == null || token.accessToken() == null || token.accessToken().isBlank() || token.expiresIn() <= 0) {
            throw new IllegalStateException("Twitch App Access Token unavailable");
        }
        cachedAppToken = new CachedAppToken(token.accessToken(), Instant.now().plusSeconds(token.expiresIn()));
        return token.accessToken();
    }

    private String safeFailure(Exception exception) {
        // Provider exception messages can contain request or credential details; only expose fixed local diagnostics.
        if (exception instanceof IllegalStateException && exception.getMessage() != null) {
            return exception.getMessage(); // Internal validation messages are fixed, non-sensitive strings.
        }
        return exception.getClass().getSimpleName();
    }

    private void createSubscription(String broadcasterId, String eventType, String appToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(appToken);
        headers.set("Client-Id", oauthProperties.clientId());

        Map<String, Object> request = Map.of(
                "type", eventType,
                "version", "1",
                "condition", Map.of("broadcaster_user_id", broadcasterId),
                "transport", Map.of(
                        "method", "webhook",
                        "callback", eventSubProperties.callbackUrl(),
                        "secret", eventSubProperties.signingSecret()));
        TwitchEventSubCreateResponse response = restTemplate.postForObject(
                SUBSCRIPTIONS_URI, new HttpEntity<>(request, headers), TwitchEventSubCreateResponse.class);
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new IllegalStateException("Twitch did not return an EventSub subscription");
        }
        TwitchEventSubCreateResponse.Subscription subscription = response.data().getFirst();
        if (subscription.id() == null || subscription.status() == null || !eventType.equals(subscription.type())) {
            throw new IllegalStateException("Twitch returned an incomplete EventSub subscription");
        }
        registry.record(subscription.id(), broadcasterId, eventType, subscription.status());
    }

    private record CachedAppToken(String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "CachedAppToken[redacted]";
        }
    }
}
