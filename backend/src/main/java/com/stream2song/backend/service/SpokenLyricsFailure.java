package com.stream2song.backend.service;

public class SpokenLyricsFailure extends RuntimeException {
    private final int status;
    private final String reason;

    public SpokenLyricsFailure(int status, String reason) {
        super(reason);
        this.status = status;
        this.reason = reason;
    }

    public int status() { return status; }
    public String reason() { return reason; }
}
