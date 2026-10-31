package com.stream2song.backend.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import com.stream2song.backend.dto.ClipLyricsResponse;
import com.stream2song.backend.dto.MusicJobResponse;

@Service
public class MusicJobService {
    static final int MAX_JOBS = 100;
    static final int AUDIO_LIMIT = 20 * 1024 * 1024;
    static final int AUDIO_TOTAL_LIMIT = 100 * 1024 * 1024;
    private static final Duration ATTEMPT_TTL = Duration.ofMinutes(15);
    private static final Duration AUDIO_TTL = Duration.ofMinutes(30);
    private static final Duration RECORD_TTL = Duration.ofMinutes(60);
    private static final int MAX_STATUS_RECOVERIES = 3;
    private static final int MAX_POLL_FAILURES = 5;

    private final TwitchBroadcasterProvider broadcasterProvider;
    private final MomentAnalysisSnapshotService snapshots;
    private final MusicGenerationProvider provider;
    private final Clock clock;
    private final PollWaiter waiter;
    private final Map<String, Job> jobs = new LinkedHashMap<>(16, .75f, true);
    private final Map<IdempotencyKey, String> idempotency = new LinkedHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(10), runnable -> {
                Thread thread = new Thread(runnable, "stream2song-music-worker");
                thread.setDaemon(true);
                return thread;
            });
    private int reservedAudioBytes;

    @Autowired
    public MusicJobService(TwitchBroadcasterProvider broadcasterProvider, MomentAnalysisSnapshotService snapshots,
            MusicGenerationProvider provider) {
        this(broadcasterProvider, snapshots, provider, Clock.systemUTC(), duration -> Thread.sleep(duration.toMillis()));
    }

    MusicJobService(TwitchBroadcasterProvider broadcasterProvider, MomentAnalysisSnapshotService snapshots,
            MusicGenerationProvider provider, Clock clock) {
        this(broadcasterProvider, snapshots, provider, clock, duration -> Thread.sleep(duration.toMillis()));
    }

    MusicJobService(TwitchBroadcasterProvider broadcasterProvider, MomentAnalysisSnapshotService snapshots,
            MusicGenerationProvider provider, Clock clock, PollWaiter waiter) {
        this.broadcasterProvider = broadcasterProvider;
        this.snapshots = snapshots;
        this.provider = provider;
        this.clock = clock;
        this.waiter = waiter;
    }

    public MusicJobResponse create(String clipId, String lyricsId) {
        if (!validId(clipId) || !validId(lyricsId)) throw failure(400, "invalid_music_request");
        TwitchBroadcasterProvider.ConnectionScope scope = broadcasterProvider.connectionScope();
        if (scope == null) throw failure(409, "twitch_account_not_connected");
        IdempotencyKey key = new IdempotencyKey(scope, clipId, lyricsId, provider.fingerprint());
        synchronized (this) {
            cleanupLocked();
            Job existing = existing(key);
            if (existing != null) return view(existing);
        }
        if (!provider.isConfigured()) throw failure(503, "music_provider_not_configured");
        MomentAnalysisSnapshotService.StoredLyrics stored = snapshots.getLyrics(scope, clipId, lyricsId);
        if (stored == null) throw failure(409, "lyrics_snapshot_expired");
        String canonicalLyrics = canonicalLyrics(stored.response());
        if (canonicalLyrics.length() > 3500) throw failure(422, "music_lyrics_too_long");
        validatePrompt(stored.response());
        if (!broadcasterProvider.isCurrentConnection(scope)) throw failure(409, "twitch_account_changed");

        synchronized (this) {
            cleanupLocked();
            Job existing = existing(key);
            if (existing != null) return view(existing);
            if (!broadcasterProvider.isCurrentConnection(scope)
                    || snapshots.getLyrics(scope, clipId, lyricsId) == null) throw failure(409, "lyrics_snapshot_stale");
            if (jobs.size() >= MAX_JOBS) throw failure(503, "music_job_capacity_exceeded");
            reserveAudio();
            Instant created = clock.instant();
            Job job = new Job(UUID.randomUUID().toString(), scope, key, stored.response(), created);
            job.reservedBytes = AUDIO_LIMIT;
            job.workerActive = true;
            jobs.put(job.id, job);
            idempotency.put(key, job.id);
            try {
                int attempt = job.attempt;
                workers.execute(() -> run(job, attempt, false));
            } catch (RejectedExecutionException exception) {
                jobs.remove(job.id);
                idempotency.remove(key);
                release(job);
                throw failure(503, "music_worker_capacity_exceeded");
            }
            return view(job);
        }
    }

    public MusicJobResponse get(String jobId) {
        TwitchBroadcasterProvider.ConnectionScope scope = broadcasterProvider.connectionScope();
        if (scope == null) throw failure(409, "twitch_account_not_connected");
        synchronized (this) {
            cleanupLocked();
            return view(owned(jobId, scope));
        }
    }

    public MusicJobResponse retry(String jobId, int expectedAttempt) {
        TwitchBroadcasterProvider.ConnectionScope scope = broadcasterProvider.connectionScope();
        if (scope == null) throw failure(409, "twitch_account_not_connected");
        if (!provider.isConfigured()) throw failure(503, "music_provider_not_configured");
        synchronized (this) {
            cleanupLocked();
            Job job = owned(jobId, scope);
            boolean statusRecovery = "STATUS".equals(job.retryMode) && job.privatePredictionId != null;
            boolean generationRetry = "GENERATION".equals(job.retryMode);
            if (job.attempt != expectedAttempt || !job.retryable || !"FAILED".equals(job.status)
                    || job.workerActive || (!statusRecovery && !generationRetry)
                    || (!statusRecovery && job.attempt >= 3)) throw failure(409, "music_retry_not_available");
            if (statusRecovery && job.statusRecoveryCount >= MAX_STATUS_RECOVERIES) throw failure(409, "music_status_recovery_exhausted");
            if (!statusRecovery && snapshots.getLyrics(scope, job.clipId, job.lyrics.lyricsId()) == null)
                throw failure(409, "lyrics_snapshot_expired");
            if (statusRecovery && !clock.instant().isBefore(job.attemptStartedAt.plus(ATTEMPT_TTL)))
                throw failure(409, "music_status_recovery_expired");
            reserveAudio();
            job.reservedBytes = AUDIO_LIMIT;
            job.status = "PENDING";
            job.reason = null;
            job.retryable = false;
            job.retryMode = null;
            job.workerActive = true;
            job.submissionStarted = false;
            if (!statusRecovery) {
                job.accepted = false;
                job.privatePredictionId = null;
                job.attempt++;
                job.attemptStartedAt = clock.instant();
                job.expiresAt = job.attemptStartedAt.plus(ATTEMPT_TTL);
            } else {
                job.statusRecoveryCount++;
            }
            try {
                int attempt = job.attempt;
                workers.execute(() -> run(job, attempt, statusRecovery));
            } catch (RejectedExecutionException exception) {
                job.workerActive = false;
                release(job);
                job.status = "FAILED";
                job.reason = "music_worker_capacity_exceeded";
                job.retryable = true;
                job.retryMode = statusRecovery ? "STATUS" : "GENERATION";
                throw failure(503, "music_worker_capacity_exceeded");
            }
            return view(job);
        }
    }

    public byte[] audio(String jobId) {
        TwitchBroadcasterProvider.ConnectionScope scope = broadcasterProvider.connectionScope();
        if (scope == null) throw failure(409, "twitch_account_not_connected");
        synchronized (this) {
            cleanupLocked();
            Job job = owned(jobId, scope);
            if (!"COMPLETED".equals(job.status) || job.audio == null) throw failure(409, "music_audio_unavailable");
            return job.audio.clone();
        }
    }

    private void run(Job job, int attempt, boolean statusRecovery) {
        try {
            synchronized (this) {
                if (!currentAttempt(job, attempt) || !"PENDING".equals(job.status)) return;
                if (!clock.instant().isBefore(job.attemptStartedAt.plus(ATTEMPT_TTL))) {
                    failLocked(job, "music_job_deadline_exceeded", false, null);
                    return;
                }
                job.status = "PROCESSING";
            }
            if (statusRecovery) {
                pollPrediction(job, attempt, job.privatePredictionId);
                return;
            }
            String lyrics = canonicalLyrics(job.lyrics);
            if (lyrics.length() > 3500) {
                fail(job, attempt, "music_lyrics_too_long", false, null);
                return;
            }
            String prompt = prompt(job.lyrics);
            synchronized (this) {
                if (!currentAttempt(job, attempt) || !"PROCESSING".equals(job.status)) return;
                if (!broadcasterProvider.isCurrentConnection(job.scope)
                        || snapshots.getLyrics(job.scope, job.clipId, job.lyrics.lyricsId()) == null) {
                    failLocked(job, "lyrics_snapshot_stale", false, null);
                    return;
                }
                if (!clock.instant().isBefore(job.attemptStartedAt.plus(ATTEMPT_TTL))) {
                    failLocked(job, "music_job_deadline_exceeded", false, null);
                    return;
                }
                job.submissionStarted = true;
            }
            MusicGenerationProvider.Prediction prediction;
            try {
                prediction = provider.submit(lyrics, prompt);
            } catch (MusicGenerationFailure failure) {
                boolean retryable = retryableReason(failure.reason());
                fail(job, attempt, failure.reason(), retryable, retryable ? "GENERATION" : null);
                return;
            }
            if (prediction == null || prediction.id() == null) {
                fail(job, attempt, "music_submission_indeterminate", false, null);
                return;
            }
            synchronized (this) {
                if (!currentAttempt(job, attempt)) return;
                job.privatePredictionId = prediction.id();
                job.accepted = true;
                job.submissionStarted = false;
                if (!clock.instant().isBefore(job.attemptStartedAt.plus(ATTEMPT_TTL))) {
                    failLocked(job, "music_job_deadline_exceeded", false, null);
                    return;
                }
                if ("EXPIRED".equals(job.status)) return;
            }
            pollPrediction(job, attempt, prediction.id());
        } catch (MusicGenerationFailure failure) {
            fail(job, attempt, failure.reason(), false, null);
        } catch (RuntimeException failure) {
            fail(job, attempt, "music_generation_failed", false, null);
        } finally {
            synchronized (this) {
                if (jobs.get(job.id) == job && job.attempt == attempt) job.workerActive = false;
            }
        }
    }

    private void pollPrediction(Job job, int attempt, String predictionId) {
        int pollFailures = 0;
        Instant deadline = job.attemptStartedAt.plus(ATTEMPT_TTL);
        while (clock.instant().isBefore(deadline) && isProcessing(job, attempt)) {
            try {
                waiter.waitFor(Duration.ofSeconds(5));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fail(job, attempt, "music_job_interrupted", false, "STATUS");
                return;
            }
            if (!isProcessing(job, attempt)) return;
            MusicGenerationProvider.Prediction prediction;
            try {
                prediction = provider.poll(predictionId);
                pollFailures = 0;
            } catch (MusicGenerationFailure failure) {
                if ("music_provider_poll_retry".equals(failure.reason()) && ++pollFailures <= MAX_POLL_FAILURES) continue;
                synchronized (this) {
                    boolean canRecover = job.statusRecoveryCount < MAX_STATUS_RECOVERIES;
                    fail(job, attempt, "music_status_recovery_required", canRecover, canRecover ? "STATUS" : null);
                }
                return;
            }
            if (prediction == null || !predictionId.equals(prediction.id())) {
                fail(job, attempt, "music_provider_response_invalid", false, null);
                return;
            }
            if ("starting".equals(prediction.status()) || "processing".equals(prediction.status())) continue;
            if ("failed".equals(prediction.status()) || "canceled".equals(prediction.status())) {
                fail(job, attempt, "music_provider_failed", true, "GENERATION");
                return;
            }
            if (!"succeeded".equals(prediction.status()) || prediction.outputUri() == null) {
                fail(job, attempt, "music_provider_response_invalid", false, null);
                return;
            }
            byte[] audio = provider.download(prediction.outputUri());
            complete(job, attempt, audio);
            return;
        }
        if (isProcessing(job, attempt)) fail(job, attempt, "music_job_deadline_exceeded", false, "STATUS");
    }

    private void complete(Job job, int attempt, byte[] audio) {
        synchronized (this) {
            if (!currentAttempt(job, attempt) || !"PROCESSING".equals(job.status)) return;
            if (audio == null || audio.length > AUDIO_LIMIT) {
                failLocked(job, "music_output_too_large", false, null);
                return;
            }
            int priorReservation = job.reservedBytes;
            job.audio = audio;
            job.audioExpires = clock.instant().plus(AUDIO_TTL);
            job.expiresAt = job.audioExpires;
            job.status = "COMPLETED";
            job.reason = null;
            job.retryable = false;
            job.retryMode = null;
            job.privatePredictionId = null;
            job.reservedBytes = audio.length;
            reservedAudioBytes -= priorReservation - job.reservedBytes;
        }
    }

    private void sleepUnused() { }

    private String canonicalLyrics(ClipLyricsResponse response) {
        StringBuilder output = new StringBuilder();
        for (ClipLyricsResponse.Section section : response.sections()) {
            String tag = switch (section.kind()) {
                case "verse" -> "[Verse]";
                case "chorus" -> "[Chorus]";
                case "bridge" -> "[Bridge]";
                default -> throw failure(422, "lyrics_structure_invalid");
            };
            output.append(tag).append('\n').append(String.join("\n", section.lines())).append("\n\n");
        }
        return output.toString().strip();
    }

    private String prompt(ClipLyricsResponse lyrics) {
        return "Original vocal song, genre " + lyrics.genre() + ", mood " + lyrics.mood() + ", energy " + lyrics.energy()
                + ". Use the supplied lyrics as written; clean audience-appropriate performance.";
    }

    private void validatePrompt(ClipLyricsResponse lyrics) {
        if (prompt(lyrics).length() > 2000) throw failure(422, "music_prompt_invalid");
    }

    private boolean retryableReason(String reason) {
        return "music_provider_rate_limited".equals(reason);
    }

    private void fail(Job job, int attempt, String reason, boolean retryable, String retryMode) {
        synchronized (this) {
            if (!currentAttempt(job, attempt)) return;
            failLocked(job, reason, retryable, retryMode);
        }
    }

    private void failLocked(Job job, String reason, boolean retryable, String retryMode) {
        if ("COMPLETED".equals(job.status) || "EXPIRED".equals(job.status)) return;
        release(job);
        job.status = "FAILED";
        job.reason = reason;
        boolean canRetry = retryable && ("STATUS".equals(retryMode)
                ? job.statusRecoveryCount < MAX_STATUS_RECOVERIES
                : "GENERATION".equals(retryMode) && job.attempt < 3);
        job.retryable = canRetry;
        job.retryMode = canRetry ? retryMode : null;
        if (!job.accepted) job.privatePredictionId = null;
    }

    private void reserveAudio() {
        if (reservedAudioBytes + AUDIO_LIMIT > AUDIO_TOTAL_LIMIT) throw failure(503, "music_storage_capacity_exceeded");
        reservedAudioBytes += AUDIO_LIMIT;
    }

    private void release(Job job) {
        if (job.reservedBytes > 0) {
            reservedAudioBytes -= job.reservedBytes;
            job.reservedBytes = 0;
        }
    }

    private Job existing(IdempotencyKey key) {
        String id = idempotency.get(key);
        return id == null ? null : jobs.get(id);
    }

    private Job owned(String id, TwitchBroadcasterProvider.ConnectionScope scope) {
        if (!validId(id)) throw new MusicGenerationFailure(404, "music_job_not_found", false);
        Job job = jobs.get(id);
        if (job == null || !job.scope.equals(scope)) throw new MusicGenerationFailure(404, "music_job_not_found", false);
        return job;
    }

    private boolean currentAttempt(Job job, int attempt) { return jobs.get(job.id) == job && job.attempt == attempt; }
    private synchronized boolean isProcessing(Job job, int attempt) { return currentAttempt(job, attempt) && "PROCESSING".equals(job.status); }
    private MusicJobResponse view(Job job) {
        return new MusicJobResponse(job.id, job.status, job.lyrics.title(), job.lyrics.genre(), job.lyrics.mood(), job.lyrics.energy(),
                List.copyOf(job.sourceMomentIds), null, job.retryable, job.retryMode, job.attempt, job.reason, job.createdAt,
                job.expiresAt, "COMPLETED".equals(job.status) ? "/api/twitch/music-jobs/" + job.id + "/audio" : null);
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void cleanup() { cleanupLocked(); }

    private void cleanupLocked() {
        Instant now = clock.instant();
        var iterator = jobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next().getValue();
            if (job.audio != null && job.audioExpires != null && !job.audioExpires.isAfter(now)) {
                job.audio = null;
                release(job);
                job.status = "EXPIRED";
                job.reason = "music_audio_expired";
                job.audioExpires = null;
            }
            if (!job.expiresAt.isAfter(now) && ("PENDING".equals(job.status) || "PROCESSING".equals(job.status)) && !job.submissionStarted) {
                String expiredStatus = job.accepted ? "FAILED" : "EXPIRED";
                job.status = expiredStatus;
                job.reason = job.accepted ? "music_job_deadline_exceeded" : "music_job_expired";
                job.retryable = false;
                job.retryMode = null;
                release(job);
            }
            if (!job.recordExpires.isAfter(now) && !job.workerActive) {
                release(job);
                job.privatePredictionId = null;
                job.audio = null;
                idempotency.remove(job.key);
                iterator.remove();
            }
        }
    }

    private boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,100}"); }
    private MusicGenerationFailure failure(int status, String reason) { return new MusicGenerationFailure(status, reason, false); }
    @PreDestroy public void shutdown() { workers.shutdownNow(); }

    interface PollWaiter { void waitFor(Duration duration) throws InterruptedException; }
    private record IdempotencyKey(TwitchBroadcasterProvider.ConnectionScope scope, String clipId, String lyricsId, String fingerprint) { }
    private static final class Job {
        final String id;
        final TwitchBroadcasterProvider.ConnectionScope scope;
        final IdempotencyKey key;
        final String clipId;
        final ClipLyricsResponse lyrics;
        final List<String> sourceMomentIds;
        final Instant createdAt;
        final Instant recordExpires;
        String status = "PENDING", reason, privatePredictionId, retryMode;
        boolean retryable, accepted, workerActive, submissionStarted;
        int attempt = 1, statusRecoveryCount, reservedBytes;
        Instant expiresAt, audioExpires, attemptStartedAt;
        byte[] audio;
        Job(String id, TwitchBroadcasterProvider.ConnectionScope scope, IdempotencyKey key, ClipLyricsResponse lyrics, Instant createdAt) {
            this.id = id; this.scope = scope; this.key = key; this.clipId = lyrics.clipId(); this.lyrics = lyrics;
            this.sourceMomentIds = lyrics.sections().stream().flatMap(section -> section.sources().stream())
                    .map(ClipLyricsResponse.SourceMoment::momentId).distinct().toList();
            this.createdAt = createdAt; this.attemptStartedAt = createdAt; this.expiresAt = createdAt.plus(ATTEMPT_TTL);
            this.recordExpires = createdAt.plus(RECORD_TTL);
        }
    }
}
