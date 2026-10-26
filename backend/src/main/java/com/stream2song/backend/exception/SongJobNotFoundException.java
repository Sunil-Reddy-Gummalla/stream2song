package com.stream2song.backend.exception;

import java.util.UUID;

public class SongJobNotFoundException extends RuntimeException {
    public SongJobNotFoundException(UUID id) {
        super("Song job not found: " + id);
    }
}
