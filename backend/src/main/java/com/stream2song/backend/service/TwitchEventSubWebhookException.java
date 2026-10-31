package com.stream2song.backend.service;

import org.springframework.http.HttpStatus;

public class TwitchEventSubWebhookException extends RuntimeException {
    private final HttpStatus status;

    public TwitchEventSubWebhookException(HttpStatus status, String safeMessage) {
        super(safeMessage);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public String reason() {
        return getMessage();
    }
}
