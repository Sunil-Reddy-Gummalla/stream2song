package com.stream2song.backend.service;

/** Safe provider-independent failure for one-clip lyric generation. */
public class LyricsGenerationFailure extends RuntimeException {
    private final int status;
    private final String reason;
    public LyricsGenerationFailure(int status, String reason) { super(reason); this.status=status; this.reason=reason; }
    public int status() { return status; }
    public String reason() { return reason; }
}
