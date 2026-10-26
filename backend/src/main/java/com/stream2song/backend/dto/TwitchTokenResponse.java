package com.stream2song.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Twitch's token response. Never return or log this provider DTO. */
public record TwitchTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("token_type") String tokenType) {

    @Override
    public String toString() {
        return "TwitchTokenResponse[redacted]";
    }
}
