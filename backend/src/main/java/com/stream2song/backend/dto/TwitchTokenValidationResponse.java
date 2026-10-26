package com.stream2song.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TwitchTokenValidationResponse(
        @JsonProperty("client_id") String clientId,
        @JsonProperty("user_id") String userId,
        String login,
        List<String> scopes) {
}
