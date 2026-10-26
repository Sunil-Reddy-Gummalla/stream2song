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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TwitchControllerTest {
    private MockMvc mockMvc;
    private MockRestServiceServer twitchServer;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        twitchServer = MockRestServiceServer.bindTo(restTemplate).build();
        TwitchOAuthProperties oauthProperties = new TwitchOAuthProperties("client-id", "client-secret",
                "http://localhost:8080/api/twitch/oauth/callback", "http://localhost:4200");
        TwitchEventSubSubscriptionRegistry registry = new TwitchEventSubSubscriptionRegistry();
        TwitchStreamSessionService sessions = new TwitchStreamSessionService();
        TwitchEventSubSubscriptionService subscriptions = new TwitchEventSubSubscriptionService(
                oauthProperties, new TwitchEventSubProperties("", ""), registry, restTemplate);
        TwitchOAuthService service = new TwitchOAuthService(oauthProperties, restTemplate);
        TwitchDashboardService dashboard = new TwitchDashboardService(service, sessions, registry);
        TwitchChatMessageService chatMessages = new TwitchChatMessageService();
        TwitchChatCollectionService chatCollection = new TwitchChatCollectionService(subscriptions, sessions, service);
        TwitchChatDashboardService chatDashboard = new TwitchChatDashboardService(service, sessions, registry, chatMessages);
        mockMvc = MockMvcBuilders.standaloneSetup(new TwitchController(service, subscriptions, dashboard, chatCollection, chatDashboard))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
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
                        {"client_id":"client-id","user_id":"broadcaster-7","login":"streamer_login","scopes":["user:read:chat","user:bot","channel:bot"],"expires_in":3600}
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
        twitchServer.verify();
    }
}
