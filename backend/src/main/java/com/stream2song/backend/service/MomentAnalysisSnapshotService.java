package com.stream2song.backend.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.stream2song.backend.dto.MemorableMomentsResponse;
import com.stream2song.backend.dto.MemorableMomentsResponse.Moment;
import com.stream2song.backend.dto.MomentInterpretationResponse;
import com.stream2song.backend.dto.ClipLyricsResponse;

@Service
public class MomentAnalysisSnapshotService {
    public static final String SESSION_CHAT_CONTEXT_REFERENCE_KEY = "session-chat-context";
    public static final String CLIP_TRANSCRIPT_CONTEXT_REFERENCE_KEY = "clip-transcript-context";
    private static final int MAX_SNAPSHOTS = 100;
    private static final int MAX_REVISIONS = 100;
    private static final Duration TTL = Duration.ofMinutes(30);
    private final Clock clock;
    private final Map<Key, Snapshot> snapshots = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Scope, Long> revisions = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<InterpretationKey, StoredInterpretation> interpretations = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<LyricsKey, StoredLyrics> lyrics = new LinkedHashMap<>(16, 0.75f, true);
    private final AtomicLong generations = new AtomicLong();

    public MomentAnalysisSnapshotService() { this(Clock.systemUTC()); }
    public MomentAnalysisSnapshotService(Clock clock) { this.clock = clock; }

    public synchronized long revision(String broadcasterId, String clipId) {
        Scope scope = new Scope(broadcasterId, 0L, clipId);
        return revision(scope);
    }

    public synchronized long revision(TwitchBroadcasterProvider.ConnectionScope connection, String clipId) {
        return revision(new Scope(connection.broadcasterId(), connection.generation(), clipId));
    }

    public synchronized void store(String broadcasterId, MemorableMomentsResponse response, long revision) {
        Scope scope = new Scope(broadcasterId, 0L, response.clipId());
        store(scope, response, revision);
    }

    public synchronized void store(TwitchBroadcasterProvider.ConnectionScope connection, MemorableMomentsResponse response, long revision) {
        store(new Scope(connection.broadcasterId(), connection.generation(), response.clipId()), response, revision);
    }

    private void store(Scope scope, MemorableMomentsResponse response, long revision) {
        if (!revision(scope).equals(revision)) {
            throw new SnapshotFailure(409, "moment_analysis_stale");
        }
        Map<String, List<EvidenceReference>> references = new LinkedHashMap<>();
        for (Moment moment : response.moments()) {
            List<EvidenceReference> values = new ArrayList<>();
            for (int i = 0; i < moment.transcriptEvidence().size(); i++) {
                var item = moment.transcriptEvidence().get(i);
                values.add(new EvidenceReference(moment.id() + "-t" + i, "transcript", truncate(item.text(), 500),
                        item.startSeconds(), item.endSeconds()));
            }
            for (int i = 0; i < moment.chatEvidence().size(); i++) {
                var item = moment.chatEvidence().get(i);
                values.add(new EvidenceReference(moment.id() + "-c" + i, "chat", truncate(item.summary(), 500),
                        item.startSeconds(), item.endSeconds()));
                for (int j = 0; j < Math.min(item.messages().size(), 3); j++) {
                    values.add(new EvidenceReference(moment.id() + "-c" + i + "m" + j, "chat_message",
                            truncate(item.messages().get(j), 240), item.startSeconds(), item.endSeconds()));
                }
            }
            if (values.size() > 20) values = values.subList(0, 20);
            references.put(moment.id(), List.copyOf(values));
        }
        List<EvidenceReference> sessionContextReferences = response.sessionChatContext().messages().stream().limit(20)
                .map(item -> new EvidenceReference(item.id(), "session_chat_context", truncate(item.text(), 200), null, null))
                .toList();
        if (!sessionContextReferences.isEmpty()) references.put(SESSION_CHAT_CONTEXT_REFERENCE_KEY, sessionContextReferences);
        List<EvidenceReference> transcriptContextReferences = response.transcriptContext().utterances().stream().limit(20)
                .map(item -> new EvidenceReference(item.id(), "clip_transcript_context", truncate(item.text(), 400),
                        item.startSeconds(), item.endSeconds())).toList();
        if (!transcriptContextReferences.isEmpty()) references.put(CLIP_TRANSCRIPT_CONTEXT_REFERENCE_KEY, transcriptContextReferences);
        Key key = new Key(scope, response.analysisId());
        snapshots.keySet().removeIf(existing -> existing.scope().equals(scope)
                && !existing.analysisId().equals(response.analysisId()));
        snapshots.put(key, new Snapshot(copy(response), Map.copyOf(references), revision, clock.instant().plus(TTL)));
        interpretations.keySet().removeIf(existing -> existing.scope().equals(scope));
        lyrics.keySet().removeIf(existing -> existing.scope().equals(scope));
        while (snapshots.size() > MAX_SNAPSHOTS) snapshots.remove(snapshots.keySet().iterator().next());
        pruneExpired();
    }

    public synchronized Snapshot get(String broadcasterId, String clipId, String analysisId) {
        return get(new Scope(broadcasterId, 0L, clipId), analysisId);
    }

    public synchronized Snapshot get(TwitchBroadcasterProvider.ConnectionScope connection, String clipId, String analysisId) {
        return get(new Scope(connection.broadcasterId(), connection.generation(), clipId), analysisId);
    }

    private Snapshot get(Scope scope, String analysisId) {
        pruneExpired();
        if (scope.broadcasterId() == null || analysisId == null) return null;
        Snapshot snapshot = snapshots.get(new Key(scope, analysisId));
        if (snapshot == null || snapshot.revision() != revision(scope)) return null;
        return snapshot;
    }

    public synchronized void storeInterpretation(TwitchBroadcasterProvider.ConnectionScope connection, String clipId,
            String analysisId, MomentInterpretationResponse response) {
        Scope scope = new Scope(connection.broadcasterId(), connection.generation(), clipId);
        Snapshot source = get(scope, analysisId);
        if (source == null || !response.analysisId().equals(analysisId) || !response.clipId().equals(clipId)) {
            throw new SnapshotFailure(409, "moment_analysis_stale");
        }
        interpretations.put(new InterpretationKey(scope, analysisId), new StoredInterpretation(copy(response), source.expiresAt()));
        lyrics.keySet().removeIf(existing -> existing.scope().equals(scope) && existing.analysisId().equals(analysisId));
        while (interpretations.size() > MAX_SNAPSHOTS) interpretations.remove(interpretations.keySet().iterator().next());
    }

    public synchronized StoredInterpretation getInterpretation(TwitchBroadcasterProvider.ConnectionScope connection,
            String clipId, String analysisId, String interpretationId) {
        pruneExpired();
        if (connection == null || analysisId == null || interpretationId == null) return null;
        Scope scope = new Scope(connection.broadcasterId(), connection.generation(), clipId);
        Snapshot source = get(scope, analysisId);
        StoredInterpretation stored = interpretations.get(new InterpretationKey(scope, analysisId));
        if (source == null || stored == null || !stored.response().interpretationId().equals(interpretationId)
                || !stored.expiresAt().isAfter(clock.instant())) return null;
        return new StoredInterpretation(copy(stored.response()), stored.expiresAt());
    }

    public synchronized void storeLyrics(TwitchBroadcasterProvider.ConnectionScope connection, ClipLyricsResponse response, Instant expiresAt) {
        Scope scope=new Scope(connection.broadcasterId(),connection.generation(),response.clipId());
        StoredInterpretation parent=getInterpretation(connection,response.clipId(),response.analysisId(),response.interpretationId());
        if(parent==null || !expiresAt.isAfter(clock.instant()) || expiresAt.isAfter(parent.expiresAt()))
            throw new SnapshotFailure(409,"lyrics_snapshot_stale");
        LyricsKey key=new LyricsKey(scope,response.analysisId(),response.interpretationId());
        lyrics.keySet().removeIf(existing->existing.scope().equals(scope)&&existing.analysisId().equals(response.analysisId()));
        lyrics.put(key,new StoredLyrics(copy(response),expiresAt));
        while(lyrics.size()>MAX_SNAPSHOTS) lyrics.remove(lyrics.keySet().iterator().next());
    }

    public synchronized StoredLyrics getLyrics(TwitchBroadcasterProvider.ConnectionScope connection,String clipId,String lyricsId) {
        pruneExpired();
        if(connection==null||clipId==null||lyricsId==null) return null;
        Scope scope=new Scope(connection.broadcasterId(),connection.generation(),clipId);
        for(var entry:lyrics.entrySet()) {
            LyricsKey key=entry.getKey(); StoredLyrics value=entry.getValue();
            if(!key.scope().equals(scope)||!value.expiresAt().isAfter(clock.instant())||!value.response().lyricsId().equals(lyricsId)) continue;
            if(getInterpretation(connection,clipId,key.analysisId(),key.interpretationId())==null) return null;
            return new StoredLyrics(copy(value.response()),value.expiresAt());
        }
        return null;
    }

    public synchronized void invalidate(String broadcasterId, String clipId) {
        if (broadcasterId == null || broadcasterId.isBlank() || clipId == null) return;
        List<Scope> scopes = new ArrayList<>();
        revisions.keySet().stream().filter(scope -> scope.broadcasterId().equals(broadcasterId)
                && scope.clipId().equals(clipId)).forEach(scopes::add);
        snapshots.keySet().stream().map(Key::scope).filter(scope -> scope.broadcasterId().equals(broadcasterId)
                && scope.clipId().equals(clipId)).forEach(scopes::add);
        interpretations.keySet().stream().map(InterpretationKey::scope).filter(scope -> scope.broadcasterId().equals(broadcasterId)
                && scope.clipId().equals(clipId)).forEach(scopes::add);
        if (scopes.isEmpty()) scopes.add(new Scope(broadcasterId, 0L, clipId));
        scopes.stream().distinct().forEach(scope -> revisions.put(scope, generations.incrementAndGet()));
        snapshots.keySet().removeIf(key -> key.scope().broadcasterId().equals(broadcasterId) && key.scope().clipId().equals(clipId));
        interpretations.keySet().removeIf(key -> key.scope().broadcasterId().equals(broadcasterId) && key.scope().clipId().equals(clipId));
        lyrics.keySet().removeIf(key -> key.scope().broadcasterId().equals(broadcasterId) && key.scope().clipId().equals(clipId));
        while (revisions.size() > MAX_REVISIONS) {
            Scope evicted = revisions.keySet().iterator().next();
            revisions.remove(evicted);
            snapshots.keySet().removeIf(key -> key.scope().equals(evicted));
            interpretations.keySet().removeIf(key -> key.scope().equals(evicted));
            lyrics.keySet().removeIf(key -> key.scope().equals(evicted));
        }
    }

    private Long revision(Scope scope) {
        Long value = revisions.computeIfAbsent(scope, ignored -> generations.incrementAndGet());
        while (revisions.size() > MAX_REVISIONS) {
            Scope evicted = revisions.keySet().iterator().next();
            revisions.remove(evicted);
            snapshots.keySet().removeIf(key -> key.scope().equals(evicted));
            interpretations.keySet().removeIf(key -> key.scope().equals(evicted));
            lyrics.keySet().removeIf(key -> key.scope().equals(evicted));
        }
        return value;
    }
    private void pruneExpired() {
        snapshots.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(clock.instant()));
        interpretations.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(clock.instant()));
        lyrics.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(clock.instant()));
    }

    private MomentInterpretationResponse copy(MomentInterpretationResponse response) {
        return new MomentInterpretationResponse(response.interpretationId(), response.analysisId(), response.clipId(),
                response.aiGenerated(), response.unverifiedInterpretation(), response.moments().stream().map(moment ->
                        new MomentInterpretationResponse.InterpretedMoment(moment.momentId(), moment.summary(),
                                moment.emotionalTone(), moment.humorExcitement(), moment.narrativeSignificance(),
                                moment.startSeconds(), moment.endSeconds(), moment.association(),
                                List.copyOf(moment.evidence()))).toList(), response.sessionContexts().stream().map(context ->
                        new MomentInterpretationResponse.InterpretedContext(context.contextId(), context.summary(),
                                context.emotionalTone(), context.humorExcitement(), context.narrativeSignificance(),
                                context.association(), List.copyOf(context.evidence()))).toList(), response.transcriptContexts().stream().map(context ->
                        new MomentInterpretationResponse.InterpretedClipContext(context.contextId(), context.summary(),
                                context.emotionalTone(), context.humorExcitement(), context.narrativeSignificance(),
                                context.startSeconds(), context.endSeconds(), context.association(), List.copyOf(context.evidence()))).toList());
    }

    private ClipLyricsResponse copy(ClipLyricsResponse response) {
        return new ClipLyricsResponse(response.lyricsId(),response.analysisId(),response.interpretationId(),response.clipId(),
                response.genre(),response.mood(),response.energy(),response.aiGenerated(),response.unverified(),response.association(),
                response.chatTimingBasis(),response.chatCorrelationExplanation(),response.title(),response.sections().stream().map(section->
                        new ClipLyricsResponse.Section(section.kind(),section.label(),List.copyOf(section.lines()),section.sources().stream().map(source->
                                new ClipLyricsResponse.SourceMoment(source.momentId(),source.startSeconds(),source.endSeconds(),source.association(),List.copyOf(source.evidence()))).toList())).toList());
    }

    private MemorableMomentsResponse copy(MemorableMomentsResponse response) {
        List<Moment> moments = response.moments().stream().map(moment -> new Moment(moment.id(), moment.description(),
                moment.startSeconds(), moment.endSeconds(), moment.score(), List.copyOf(moment.transcriptEvidence()),
                moment.chatEvidence().stream().map(chat -> new MemorableMomentsResponse.ChatEvidence(chat.windowStartUtc(),
                        chat.windowEndUtc(), chat.startSeconds(), chat.endSeconds(), chat.score(), chat.summary(),
                        chat.explanation(), chat.messageCount(), List.copyOf(chat.messages()))).toList(),
                moment.sourceClipId(), moment.association(), moment.selectionExplanation(), List.copyOf(moment.contributors()))).toList();
        return new MemorableMomentsResponse(response.analysisId(), response.clipId(), response.source(),
                response.association(), response.chatCorrelation(), response.chatTimingBasis(),
                response.chatCorrelationExplanation(), response.heuristicOnly(), response.scoreScale(),
                response.message(), List.copyOf(moments), new MemorableMomentsResponse.SessionChatContext(
                        response.sessionChatContext().availableCount(), response.sessionChatContext().analyzedCount(),
                        response.sessionChatContext().suppliedCount(), response.sessionChatContext().truncatedCount(),
                        response.sessionChatContext().messages().stream().map(message ->
                                new MemorableMomentsResponse.SessionChatMessage(message.id(), message.text())).toList(),
                        response.sessionChatContext().association()), new MemorableMomentsResponse.TranscriptContext(
                        response.transcriptContext().availableCount(), response.transcriptContext().analyzedCount(),
                        response.transcriptContext().suppliedCount(), response.transcriptContext().truncatedCount(),
                        response.transcriptContext().suppliedCharacters(), response.transcriptContext().truncatedCharacters(),
                        response.transcriptContext().association(), response.transcriptContext().sourceType(),
                        response.transcriptContext().utterances().stream().map(item ->
                                new MemorableMomentsResponse.TranscriptUtterance(item.id(), item.text(),
                                        item.startSeconds(), item.endSeconds())).toList()));
    }

    private static String truncate(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    public record EvidenceReference(String id, String kind, String excerpt, Double startSeconds, Double endSeconds) { }
    public record Snapshot(MemorableMomentsResponse response, Map<String, List<EvidenceReference>> references,
            long revision, Instant expiresAt) { }
    private record Scope(String broadcasterId, long generation, String clipId) { }
    private record Key(Scope scope, String analysisId) { }
    private record InterpretationKey(Scope scope, String analysisId) { }
    public record StoredInterpretation(MomentInterpretationResponse response, Instant expiresAt) { }
    private record LyricsKey(Scope scope,String analysisId,String interpretationId) { }
    public record StoredLyrics(ClipLyricsResponse response,Instant expiresAt) { }

    public static class SnapshotFailure extends RuntimeException {
        private final int status;
        private final String reason;
        public SnapshotFailure(int status, String reason) { super(reason); this.status = status; this.reason = reason; }
        public int status() { return status; }
        public String reason() { return reason; }
    }
}
