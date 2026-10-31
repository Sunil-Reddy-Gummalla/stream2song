package com.stream2song.backend.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.stream2song.backend.dto.MemorableMomentsResponse;
import com.stream2song.backend.dto.MomentInterpretationResponse;
import com.stream2song.backend.dto.MomentInterpretationResponse.EvidenceCitation;
import com.stream2song.backend.dto.MomentInterpretationResponse.InterpretedMoment;
import com.stream2song.backend.dto.MomentInterpretationResponse.InterpretedContext;
import com.stream2song.backend.dto.MomentInterpretationResponse.InterpretedClipContext;

@Service
public class MomentInterpretationService {
    private final TwitchBroadcasterProvider broadcasterProvider;
    private final MomentAnalysisSnapshotService snapshots;
    private final MomentInterpretationProvider provider;

    public MomentInterpretationService(TwitchBroadcasterProvider broadcasterProvider,
            MomentAnalysisSnapshotService snapshots, MomentInterpretationProvider provider) {
        this.broadcasterProvider = broadcasterProvider;
        this.snapshots = snapshots;
        this.provider = provider;
    }

    public MomentInterpretationResponse interpret(String clipId, String analysisId) {
        if (clipId == null || !clipId.matches("[A-Za-z0-9_-]{1,100}") || analysisId == null
                || !analysisId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new InterpretationFailure(400, "invalid_moment_analysis_id");
        }
        TwitchBroadcasterProvider.ConnectionScope connection = broadcasterProvider.connectionScope();
        String broadcasterId = connection == null ? null : connection.broadcasterId();
        if (broadcasterId == null || broadcasterId.isBlank()) {
            throw new InterpretationFailure(409, "twitch_account_not_connected");
        }
        if (!provider.isConfigured()) throw new InterpretationFailure(503, "interpretation_provider_not_configured");
        MomentAnalysisSnapshotService.Snapshot snapshot = snapshots.get(connection, clipId, analysisId);
        if (snapshot == null) throw new InterpretationFailure(409, "moment_analysis_expired");
        MemorableMomentsResponse extraction = snapshot.response();
        boolean hasSessionContext = extraction.sessionChatContext().suppliedCount() > 0;
        boolean hasTranscriptContext = extraction.transcriptContext().suppliedCount() > 0;
        if (extraction.moments().isEmpty() && !hasSessionContext && !hasTranscriptContext) throw new InterpretationFailure(422, "moment_analysis_empty");
        if (extraction.moments().size() > 10) throw new InterpretationFailure(409, "moment_analysis_invalid");
        List<MomentInterpretationProvider.InputMoment> inputs = new ArrayList<>(extraction.moments().stream().map(moment -> {
            List<MomentInterpretationProvider.Evidence> evidence = snapshot.references()
                    .getOrDefault(moment.id(), List.of()).stream().limit(10).map(ref ->
                            new MomentInterpretationProvider.Evidence(ref.id(), ref.kind(), ref.excerpt(),
                                    ref.startSeconds(), ref.endSeconds())).toList();
            return new MomentInterpretationProvider.InputMoment(moment.id(), moment.startSeconds(),
                    moment.endSeconds(), evidence, "clip_moment");
        }).toList());
        if (hasSessionContext) {
            List<MomentInterpretationProvider.Evidence> evidence = snapshot.references()
                    .getOrDefault(MomentAnalysisSnapshotService.SESSION_CHAT_CONTEXT_REFERENCE_KEY, List.of()).stream()
                    .map(ref -> new MomentInterpretationProvider.Evidence(ref.id(), ref.kind(), ref.excerpt(), null, null)).toList();
            if (evidence.isEmpty() || evidence.size() > 20) throw new InterpretationFailure(409, "moment_analysis_invalid");
            inputs.add(new MomentInterpretationProvider.InputMoment(
                    MomentAnalysisSnapshotService.SESSION_CHAT_CONTEXT_REFERENCE_KEY, null, null, evidence, "session_context"));
        }
        if (hasTranscriptContext) {
            List<MomentInterpretationProvider.Evidence> evidence = snapshot.references()
                    .getOrDefault(MomentAnalysisSnapshotService.CLIP_TRANSCRIPT_CONTEXT_REFERENCE_KEY, List.of()).stream()
                    .map(ref -> new MomentInterpretationProvider.Evidence(ref.id(), ref.kind(), ref.excerpt(),
                            ref.startSeconds(), ref.endSeconds())).toList();
            if (evidence.isEmpty() || evidence.size() > 20) throw new InterpretationFailure(409, "moment_analysis_invalid");
            inputs.add(new MomentInterpretationProvider.InputMoment(
                    MomentAnalysisSnapshotService.CLIP_TRANSCRIPT_CONTEXT_REFERENCE_KEY, null, null, evidence, "clip_context"));
        }
        List<MomentInterpretationProvider.Interpretation> output;
        try {
            output = provider.interpret(inputs, extraction.chatCorrelationExplanation());
        } catch (OpenAiMomentInterpretationProvider.InterpretationFailure failure) {
            throw new InterpretationFailure(failure.status(), failure.reason());
        }
        if (!broadcasterProvider.isCurrentConnection(connection) || snapshots.get(connection, clipId, analysisId) == null) {
            throw new InterpretationFailure(409, "moment_analysis_stale");
        }
        ValidatedResults results = validateAndNormalize(extraction, snapshot, output, hasSessionContext, hasTranscriptContext);
        MomentInterpretationResponse response = new MomentInterpretationResponse(UUID.randomUUID().toString(), analysisId, clipId,
                true, true, List.copyOf(results.moments()), List.copyOf(results.contexts()), List.copyOf(results.clipContexts()));
        try { snapshots.storeInterpretation(connection, clipId, analysisId, response); }
        catch (MomentAnalysisSnapshotService.SnapshotFailure failure) {
            throw new InterpretationFailure(failure.status(), failure.reason());
        }
        if (!broadcasterProvider.isCurrentConnection(connection)
                || snapshots.getInterpretation(connection, clipId, analysisId, response.interpretationId()) == null) {
            throw new InterpretationFailure(409, "moment_analysis_stale");
        }
        return response;
    }

    private ValidatedResults validateAndNormalize(MemorableMomentsResponse extraction,
            MomentAnalysisSnapshotService.Snapshot snapshot,
            List<MomentInterpretationProvider.Interpretation> output, boolean hasSessionContext, boolean hasTranscriptContext) {
        int expectedOutputCount = extraction.moments().size() + (hasSessionContext ? 1 : 0) + (hasTranscriptContext ? 1 : 0);
        if (output == null || output.size() != expectedOutputCount || output.size() > 12) {
            throw new InterpretationFailure(502, "interpretation_output_invalid");
        }
        Set<String> seenMoments = new HashSet<>();
        List<InterpretedMoment> result = new ArrayList<>();
        List<InterpretedContext> contexts = new ArrayList<>();
        List<InterpretedClipContext> clipContexts = new ArrayList<>();
        for (MomentInterpretationProvider.Interpretation interpretation : output) {
            if (interpretation == null || interpretation.momentId() == null || interpretation.kind() == null
                    || !Set.of("clip_moment", "session_context", "clip_context").contains(interpretation.kind())
                    || !seenMoments.add(interpretation.kind() + ":" + interpretation.momentId())) {
                throw new InterpretationFailure(502, "interpretation_output_invalid");
            }
            if ("session_context".equals(interpretation.kind())) {
                if (!hasSessionContext || !MomentAnalysisSnapshotService.SESSION_CHAT_CONTEXT_REFERENCE_KEY.equals(interpretation.momentId())) {
                    throw new InterpretationFailure(502, "interpretation_output_invalid");
                }
                contexts.add(validateContext(interpretation, snapshot));
                continue;
            }
            if ("clip_context".equals(interpretation.kind())) {
                if (!hasTranscriptContext || !MomentAnalysisSnapshotService.CLIP_TRANSCRIPT_CONTEXT_REFERENCE_KEY.equals(interpretation.momentId())) {
                    throw new InterpretationFailure(502, "interpretation_output_invalid");
                }
                clipContexts.add(validateClipContext(interpretation, snapshot, extraction.transcriptContext().association()));
                continue;
            }
            var moment = extraction.moments().stream().filter(item -> item.id().equals(interpretation.momentId()))
                    .findFirst().orElseThrow(() -> new InterpretationFailure(502, "interpretation_output_invalid"));
            String summary = bounded(interpretation.summary(), 600);
            String tone = bounded(interpretation.emotionalTone(), 160);
            String humor = bounded(interpretation.humorExcitement(), 300);
            String significance = bounded(interpretation.narrativeSignificance(), 400);
            List<MomentAnalysisSnapshotService.EvidenceReference> available = snapshot.references()
                    .getOrDefault(moment.id(), List.of());
            Set<String> allowed = new HashSet<>(available.stream().map(MomentAnalysisSnapshotService.EvidenceReference::id).toList());
            if (interpretation.evidenceReferences() == null || interpretation.evidenceReferences().isEmpty()
                    || interpretation.evidenceReferences().size() > 10) {
                throw new InterpretationFailure(502, "interpretation_output_invalid");
            }
            Set<String> cited = new HashSet<>();
            List<EvidenceCitation> citations = new ArrayList<>();
            for (String reference : interpretation.evidenceReferences()) {
                if (reference == null || !allowed.contains(reference) || !cited.add(reference)) {
                    throw new InterpretationFailure(502, "interpretation_output_invalid");
                }
                var evidence = available.stream().filter(value -> value.id().equals(reference)).findFirst().orElseThrow();
                citations.add(new EvidenceCitation(evidence.id(), evidence.kind(), evidence.excerpt(),
                        evidence.startSeconds(), evidence.endSeconds()));
            }
            result.add(new InterpretedMoment(moment.id(), summary, tone, humor, significance,
                    moment.startSeconds(), moment.endSeconds(), moment.association(), List.copyOf(citations)));
        }
        if (result.size() != extraction.moments().size() || contexts.size() != (hasSessionContext ? 1 : 0)
                || clipContexts.size() != (hasTranscriptContext ? 1 : 0)) {
            throw new InterpretationFailure(502, "interpretation_output_invalid");
        }
        return new ValidatedResults(result, contexts, clipContexts);
    }

    private InterpretedContext validateContext(MomentInterpretationProvider.Interpretation interpretation,
            MomentAnalysisSnapshotService.Snapshot snapshot) {
        String summary = bounded(interpretation.summary(), 600);
        String tone = bounded(interpretation.emotionalTone(), 160);
        String humor = bounded(interpretation.humorExcitement(), 300);
        String significance = bounded(interpretation.narrativeSignificance(), 400);
        List<MomentAnalysisSnapshotService.EvidenceReference> available = snapshot.references()
                .getOrDefault(MomentAnalysisSnapshotService.SESSION_CHAT_CONTEXT_REFERENCE_KEY, List.of());
        if (interpretation.evidenceReferences() == null || interpretation.evidenceReferences().isEmpty()
                || interpretation.evidenceReferences().size() > 20) throw new InterpretationFailure(502, "interpretation_output_invalid");
        Set<String> allowed = new HashSet<>(available.stream().map(MomentAnalysisSnapshotService.EvidenceReference::id).toList());
        Set<String> cited = new HashSet<>();
        List<MomentInterpretationResponse.EvidenceCitation> citations = new ArrayList<>();
        for (String reference : interpretation.evidenceReferences()) {
            if (reference == null || !allowed.contains(reference) || !cited.add(reference)) {
                throw new InterpretationFailure(502, "interpretation_output_invalid");
            }
            var evidence = available.stream().filter(value -> value.id().equals(reference)).findFirst().orElseThrow();
            citations.add(new MomentInterpretationResponse.EvidenceCitation(evidence.id(), evidence.kind(), evidence.excerpt(), null, null));
        }
        return new InterpretedContext(interpretation.momentId(), summary, tone, humor, significance,
                "unknown", List.copyOf(citations));
    }

    private InterpretedClipContext validateClipContext(MomentInterpretationProvider.Interpretation interpretation,
            MomentAnalysisSnapshotService.Snapshot snapshot, String association) {
        String summary = bounded(interpretation.summary(), 600);
        String tone = bounded(interpretation.emotionalTone(), 160);
        String humor = bounded(interpretation.humorExcitement(), 300);
        String significance = bounded(interpretation.narrativeSignificance(), 400);
        List<MomentAnalysisSnapshotService.EvidenceReference> available = snapshot.references()
                .getOrDefault(MomentAnalysisSnapshotService.CLIP_TRANSCRIPT_CONTEXT_REFERENCE_KEY, List.of());
        if (interpretation.evidenceReferences() == null || interpretation.evidenceReferences().isEmpty()
                || interpretation.evidenceReferences().size() > 20) throw new InterpretationFailure(502, "interpretation_output_invalid");
        Set<String> allowed = new HashSet<>(available.stream().map(MomentAnalysisSnapshotService.EvidenceReference::id).toList());
        Set<String> cited = new HashSet<>();
        List<EvidenceCitation> citations = new ArrayList<>();
        for (String reference : interpretation.evidenceReferences()) {
            if (reference == null || !allowed.contains(reference) || !cited.add(reference)) throw new InterpretationFailure(502, "interpretation_output_invalid");
            var evidence = available.stream().filter(value -> value.id().equals(reference)).findFirst().orElseThrow();
            citations.add(new EvidenceCitation(evidence.id(), evidence.kind(), evidence.excerpt(), evidence.startSeconds(), evidence.endSeconds()));
        }
        return new InterpretedClipContext(interpretation.momentId(), summary, tone, humor, significance,
                null, null, association == null ? "unknown" : association, List.copyOf(citations));
    }

    private String bounded(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new InterpretationFailure(502, "interpretation_output_invalid");
        }
        return value;
    }

    private record ValidatedResults(List<InterpretedMoment> moments, List<InterpretedContext> contexts,
            List<InterpretedClipContext> clipContexts) { }

    public static class InterpretationFailure extends RuntimeException {
        private final int status;
        private final String reason;
        public InterpretationFailure(int status, String reason) { super(reason); this.status = status; this.reason = reason; }
        public int status() { return status; }
        public String reason() { return reason; }
    }
}
