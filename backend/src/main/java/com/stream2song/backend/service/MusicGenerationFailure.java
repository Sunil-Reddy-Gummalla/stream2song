package com.stream2song.backend.service;

public class MusicGenerationFailure extends RuntimeException {
    private final int status; private final String reason; private final boolean acceptanceUnknown;
    public MusicGenerationFailure(int status,String reason,boolean acceptanceUnknown){super(reason);this.status=status;this.reason=reason;this.acceptanceUnknown=acceptanceUnknown;}
    public int status(){return status;} public String reason(){return reason;} public boolean acceptanceUnknown(){return acceptanceUnknown;}
}
