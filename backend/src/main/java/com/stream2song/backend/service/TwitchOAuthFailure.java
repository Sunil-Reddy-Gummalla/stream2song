package com.stream2song.backend.service;

public class TwitchOAuthFailure extends RuntimeException {
    private final String reason;

    public TwitchOAuthFailure(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
