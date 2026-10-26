package com.stream2song.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Twitch app token response; never expose or log this provider DTO. */
public record TwitchAppAccessTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("token_type") String tokenType) {

    @Override
    public String toString() {
        return "TwitchAppAccessTokenResponse[redacted]";
    }
}
