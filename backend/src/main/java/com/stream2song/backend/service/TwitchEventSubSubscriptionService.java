package com.stream2song.backend.service;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.stream2song.backend.config.TwitchEventSubProperties;
import com.stream2song.backend.config.TwitchOAuthProperties;
import com.stream2song.backend.dto.TwitchEventSubCreateResponse;
import com.stream2song.backend.dto.TwitchEventSubListResponse;
import com.stream2song.backend.dto.TwitchStreamsResponse;

@Service
public class TwitchEventSubSubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(TwitchEventSubSubscriptionService.class);
    private static final URI SUBSCRIPTIONS_URI = URI.create("https://api.twitch.tv/helix/eventsub/subscriptions");
    private static final URI STREAMS_URI = URI.create("https://api.twitch.tv/helix/streams");
    private static final List<String> STREAM_EVENT_TYPES = List.of("stream.online", "stream.offline");
    private static final String CHAT_EVENT_TYPE = "channel.chat.message";
    private static final String EVENTSUB_VERSION = "1";
    private static final int MAX_SUBSCRIPTION_PAGES = 20;

    private final TwitchOAuthProperties oauthProperties;
    private final TwitchEventSubProperties eventSubProperties;
    private final TwitchEventSubSubscriptionRegistry registry;
    private final RestTemplate restTemplate;
    private final TwitchAppAccessTokenService appAccessTokenService;

    public TwitchEventSubSubscriptionService(
            TwitchOAuthProperties oauthProperties,
            TwitchEventSubProperties eventSubProperties,
            TwitchEventSubSubscriptionRegistry registry,
            TwitchAppAccessTokenService appAccessTokenService,
            RestTemplate twitchRestTemplate) {
        this.oauthProperties = oauthProperties;
        this.eventSubProperties = eventSubProperties;
        this.registry = registry;
        this.appAccessTokenService = appAccessTokenService;
        this.restTemplate = twitchRestTemplate;
    }

    public synchronized void subscribeToStreamEvents(String broadcasterId) {
        if (broadcasterId == null || broadcasterId.isBlank()) return;
        log.debug("Ensuring Twitch stream EventSub subscriptions for broadcasterId={}", broadcasterId);
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
            log.warn("Could not obtain Twitch App Access Token for stream subscriptions: broadcasterId={} httpStatus={}",
                    broadcasterId, exception.getStatusCode().value());
            String status = statusForHttp(exception.getStatusCode().value());
            STREAM_EVENT_TYPES.forEach(type -> registry.markStatus(broadcasterId, type, status));
            return;
        } catch (RestClientException | IllegalStateException exception) {
            log.warn("Could not obtain Twitch App Access Token for stream subscriptions: broadcasterId={} reason={}",
                    broadcasterId, safeFailure(exception));
            STREAM_EVENT_TYPES.forEach(type -> registry.markStatus(broadcasterId, type, "failed"));
            return;
        }

        for (String eventType : STREAM_EVENT_TYPES) {
            if (registry.isActive(broadcasterId, eventType)) continue;
            try {
                createSubscription(broadcasterId, eventType, appToken);
                log.info("Twitch EventSub subscription created: type={} broadcasterId={} status={}", eventType,
                        broadcasterId, registry.statusesFor(broadcasterId).get(eventType));
            } catch (RestClientResponseException exception) {
                if (exception.getStatusCode().value() == 409) {
                    reconcileConflict(broadcasterId, eventType,
                            Map.of("broadcaster_user_id", broadcasterId), appToken,
                            () -> createSubscription(broadcasterId, eventType, appToken));
                } else {
                    markCreationFailure(broadcasterId, eventType, exception);
                }
            } catch (RestClientException | IllegalStateException exception) {
                log.warn("Twitch EventSub subscription failed: type={} broadcasterId={} reason={}", eventType,
                        broadcasterId, safeFailure(exception));
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
        String appToken;
        try {
            appToken = getAppAccessToken();
        } catch (RestClientResponseException exception) {
            markCreationFailure(broadcasterId, CHAT_EVENT_TYPE, exception);
            return;
        } catch (RestClientException | IllegalStateException exception) {
            registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "failed");
            log.warn("Could not obtain Twitch App Access Token for chat subscription: broadcasterId={} reason={}",
                    broadcasterId, safeFailure(exception));
            return;
        }

        try {
            createChatSubscription(broadcasterId, botUserId, appToken);
            log.info("Twitch EventSub subscription created: type={} broadcasterId={} status={}", CHAT_EVENT_TYPE,
                    broadcasterId, registry.statusesFor(broadcasterId).get(CHAT_EVENT_TYPE));
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) {
                reconcileConflict(broadcasterId, CHAT_EVENT_TYPE,
                        Map.of("broadcaster_user_id", broadcasterId, "user_id", botUserId),
                        appToken, () -> createChatSubscription(broadcasterId, botUserId, appToken));
            } else {
                markCreationFailure(broadcasterId, CHAT_EVENT_TYPE, exception);
            }
        } catch (RestClientException | IllegalStateException exception) {
            log.warn("Twitch EventSub subscription failed: type={} broadcasterId={} reason={}", CHAT_EVENT_TYPE,
                    broadcasterId, safeFailure(exception));
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
            log.info("Twitch EventSub subscription stopped: type={} broadcasterId={}", CHAT_EVENT_TYPE, broadcasterId);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                registry.remove(id);
                registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "stopped");
            } else {
                log.warn("Could not stop Twitch EventSub subscription: type={} broadcasterId={} httpStatus={}",
                        CHAT_EVENT_TYPE, broadcasterId, exception.getStatusCode().value());
                registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "failed");
            }
        } catch (RestClientException | IllegalStateException exception) {
            log.warn("Could not stop Twitch EventSub subscription: type={} broadcasterId={} reason={}",
                    CHAT_EVENT_TYPE, broadcasterId, safeFailure(exception));
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
            log.warn("Twitch Get Streams request failed: broadcasterId={} httpStatus={}", broadcasterId,
                    exception.getStatusCode().value());
            registry.markStatus(broadcasterId, CHAT_EVENT_TYPE, "failed");
            return null;
        } catch (RestClientException | IllegalStateException exception) {
            log.warn("Twitch Get Streams request failed: broadcasterId={} reason={}", broadcasterId,
                    safeFailure(exception));
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
                "version", EVENTSUB_VERSION,
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

    private String getAppAccessToken() {
        return appAccessTokenService.accessToken();
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
                "version", EVENTSUB_VERSION,
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

    private void reconcileConflict(String broadcasterId, String eventType, Map<String, String> expectedCondition,
            String appToken, SubscriptionCreator retryCreate) {
        log.warn("Twitch EventSub create returned HTTP 409: type={} broadcasterId={} httpStatus=409",
                eventType, broadcasterId);

        Optional<TwitchEventSubListResponse.Subscription> existing;
        try {
            existing = findEquivalentSubscription(eventType, expectedCondition, appToken);
        } catch (RestClientResponseException exception) {
            String status = statusForHttp(exception.getStatusCode().value());
            registry.markStatus(broadcasterId, eventType, status.equals("failed") ? "conflict_unresolved" : status);
            log.warn("Could not reconcile Twitch EventSub conflict: type={} broadcasterId={} httpStatus={} lookupStatus={}",
                    eventType, broadcasterId, exception.getStatusCode().value(), status);
            return;
        } catch (RestClientException | IllegalStateException exception) {
            registry.markStatus(broadcasterId, eventType, "conflict_unresolved");
            log.warn("Could not reconcile Twitch EventSub conflict: type={} broadcasterId={} reason={}",
                    eventType, broadcasterId, safeFailure(exception));
            return;
        }

        if (existing.isEmpty()) {
            registry.markStatus(broadcasterId, eventType, "conflict_unresolved");
            log.warn("Twitch EventSub conflict has no equivalent configured subscription: type={} broadcasterId={} httpStatus=409",
                    eventType, broadcasterId);
            return;
        }

        TwitchEventSubListResponse.Subscription subscription = existing.get();
        if ("enabled".equals(subscription.status())
                || "webhook_callback_verification_pending".equals(subscription.status())) {
            registry.record(subscription.id(), broadcasterId, eventType, subscription.status());
            log.info("Reconciled existing Twitch EventSub subscription: type={} broadcasterId={} status={} httpStatus=409",
                    eventType, broadcasterId, subscription.status());
            return;
        }

        log.warn("Matching Twitch EventSub subscription is unusable; attempting one bounded replacement: type={} broadcasterId={} status={}",
                eventType, broadcasterId, subscription.status());
        try {
            deleteSubscription(subscription.id(), appToken);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 404) {
                String status = statusForHttp(exception.getStatusCode().value());
                registry.markStatus(broadcasterId, eventType, status);
                log.warn("Could not remove unusable Twitch EventSub subscription: type={} broadcasterId={} status={} httpStatus={}",
                        eventType, broadcasterId, subscription.status(), exception.getStatusCode().value());
                return;
            }
        } catch (RestClientException | IllegalStateException exception) {
            registry.markStatus(broadcasterId, eventType, "failed");
            log.warn("Could not remove unusable Twitch EventSub subscription: type={} broadcasterId={} status={} reason={}",
                    eventType, broadcasterId, subscription.status(), safeFailure(exception));
            return;
        }
        registry.remove(subscription.id());

        try {
            retryCreate.create();
            log.info("Replaced unusable Twitch EventSub subscription: type={} broadcasterId={} status={}",
                    eventType, broadcasterId, registry.statusesFor(broadcasterId).get(eventType));
        } catch (RestClientResponseException exception) {
            markCreationFailure(broadcasterId, eventType, exception);
        } catch (RestClientException | IllegalStateException exception) {
            registry.markStatus(broadcasterId, eventType, "failed");
            log.warn("Bounded Twitch EventSub replacement failed: type={} broadcasterId={} reason={}",
                    eventType, broadcasterId, safeFailure(exception));
        }
    }

    private Optional<TwitchEventSubListResponse.Subscription> findEquivalentSubscription(
            String eventType, Map<String, String> expectedCondition, String appToken) {
        List<TwitchEventSubListResponse.Subscription> subscriptions = new java.util.ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < MAX_SUBSCRIPTION_PAGES; page++) {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUri(SUBSCRIPTIONS_URI)
                    .queryParam("type", eventType);
            if (cursor != null) builder.queryParam("after", cursor);
            URI uri = builder.build().encode().toUri();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(appToken);
            headers.set("Client-Id", oauthProperties.clientId());
            TwitchEventSubListResponse response = restTemplate.exchange(uri,
                    org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers),
                    TwitchEventSubListResponse.class).getBody();
            if (response == null || response.data() == null) {
                throw new IllegalStateException("Twitch returned an incomplete EventSub subscriptions response");
            }
            subscriptions.addAll(response.data());
            cursor = response.pagination() == null ? null : response.pagination().cursor();
            if (cursor == null || cursor.isBlank()) break;
            if (!seenCursors.add(cursor)) {
                throw new IllegalStateException("Twitch repeated an EventSub pagination cursor");
            }
            if (page == MAX_SUBSCRIPTION_PAGES - 1) {
                throw new IllegalStateException("Twitch EventSub subscriptions lookup exceeded the page limit");
            }
        }

        return subscriptions.stream()
                .filter(subscription -> eventType.equals(subscription.type()))
                .filter(subscription -> EVENTSUB_VERSION.equals(subscription.version()))
                .filter(subscription -> expectedCondition.equals(subscription.condition()))
                .filter(subscription -> subscription.transport() != null
                        && "webhook".equals(subscription.transport().method())
                        && eventSubProperties.callbackUrl().equals(subscription.transport().callback()))
                .sorted(java.util.Comparator.comparingInt(this::subscriptionStatusPriority))
                .findFirst();
    }

    private int subscriptionStatusPriority(TwitchEventSubListResponse.Subscription subscription) {
        return switch (subscription.status()) {
            case "enabled" -> 0;
            case "webhook_callback_verification_pending" -> 1;
            default -> 2;
        };
    }

    private void deleteSubscription(String id, String appToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(appToken);
        headers.set("Client-Id", oauthProperties.clientId());
        URI uri = UriComponentsBuilder.fromUri(SUBSCRIPTIONS_URI).queryParam("id", id).build().toUri();
        restTemplate.exchange(uri, org.springframework.http.HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class);
    }

    private void markCreationFailure(String broadcasterId, String eventType, RestClientResponseException exception) {
        int httpStatus = exception.getStatusCode().value();
        String state = statusForHttp(httpStatus);
        registry.markStatus(broadcasterId, eventType, state);
        log.warn("Twitch EventSub subscription failed: type={} broadcasterId={} httpStatus={} state={}",
                eventType, broadcasterId, httpStatus, state);
    }

    private String statusForHttp(int httpStatus) {
        return switch (httpStatus) {
            case 401 -> "unauthorized";
            case 403 -> "authorization_required";
            case 409 -> "conflict_unresolved";
            default -> "failed";
        };
    }

    @FunctionalInterface
    private interface SubscriptionCreator {
        void create();
    }

}
