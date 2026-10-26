package com.stream2song.backend.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.stream2song.backend.exception.SongJobNotFoundException;
import com.stream2song.backend.model.SongJob;

@Service
public class SongJobService {
    private final Map<UUID, SongJob> jobs = new ConcurrentHashMap<>();

    public SongJob create(String channelName) {
        SongJob job = new SongJob(UUID.randomUUID(), channelName, SongJob.Status.PENDING, Instant.now());
        jobs.put(job.id(), job);
        return job;
    }

    public SongJob get(UUID id) {
        SongJob job = jobs.get(id);
        if (job == null) {
            throw new SongJobNotFoundException(id);
        }
        return job;
    }

    public List<SongJob> list() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(SongJob::createdAt).reversed())
                .toList();
    }
}
