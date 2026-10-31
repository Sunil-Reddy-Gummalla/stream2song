package com.stream2song.backend.dto;

import java.time.Instant;
import java.util.List;

public record MusicJobResponse(String jobId,String status,String title,String genre,String mood,String energy,
        List<String> sourceMomentIds,Integer progressPercent,boolean retryable,String retryMode,int attempt,String reason,Instant createdAt,Instant expiresAt,
        String audioPath) { }
