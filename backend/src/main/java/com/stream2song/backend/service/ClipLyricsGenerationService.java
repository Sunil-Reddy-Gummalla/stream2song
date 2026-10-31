package com.stream2song.backend.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.UUID;

import org.springframework.stereotype.Service;
import com.stream2song.backend.dto.ClipLyricsResponse;
import com.stream2song.backend.dto.MomentInterpretationResponse;

@Service
public class ClipLyricsGenerationService {
    private static final Set<String> GENRES = Set.of("pop", "rock", "hip_hop", "electronic", "folk");
    private static final Set<String> MOODS = Set.of("playful", "celebratory", "reflective", "dramatic");
    private static final Set<String> ENERGIES = Set.of("low", "medium", "high");
    private static final Pattern WORDS = Pattern.compile("[^\\p{L}\\p{N}']+");
    private final TwitchBroadcasterProvider broadcasterProvider;
    private final MomentAnalysisSnapshotService snapshots;
    private final LyricsGenerationProvider provider;
    public ClipLyricsGenerationService(TwitchBroadcasterProvider broadcasterProvider, MomentAnalysisSnapshotService snapshots,
            LyricsGenerationProvider provider) { this.broadcasterProvider=broadcasterProvider; this.snapshots=snapshots; this.provider=provider; }

    public ClipLyricsResponse generate(String clipId, LyricsRequest request) {
        if (clipId == null || !clipId.matches("[A-Za-z0-9_-]{1,100}") || request == null
                || !validId(request.analysisId()) || !validId(request.interpretationId())
                || (request.genre()!=null && !GENRES.contains(request.genre()))
                || (request.mood()!=null && !MOODS.contains(request.mood()))
                || (request.energy()!=null && !ENERGIES.contains(request.energy())))
            throw new LyricsGenerationFailure(400,"invalid_lyrics_request");
        request = new LyricsRequest(request.analysisId(), request.interpretationId(),
                request.genre()==null?"pop":request.genre(), request.mood()==null?"playful":request.mood(),
                request.energy()==null?"medium":request.energy());
        var scope = broadcasterProvider.connectionScope();
        if (scope == null) throw new LyricsGenerationFailure(409,"twitch_account_not_connected");
        if (!provider.isConfigured()) throw new LyricsGenerationFailure(503,"lyrics_provider_not_configured");
        var stored = snapshots.getInterpretation(scope, clipId, request.analysisId(), request.interpretationId());
        var source = snapshots.get(scope, clipId, request.analysisId());
        if (stored == null || source == null) throw new LyricsGenerationFailure(409,"interpretation_snapshot_expired");
        var interpretation=stored.response();
        Map<String, MomentInterpretationResponse.InterpretedMoment> moments = interpretation.moments().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(MomentInterpretationResponse.InterpretedMoment::momentId, m->m));
        Map<String, MomentInterpretationResponse.InterpretedMoment> sourceItems = new java.util.LinkedHashMap<>(moments);
        for (var context : interpretation.sessionContexts()) {
            var contextSource = new MomentInterpretationResponse.InterpretedMoment(context.contextId(), context.summary(),
                    context.emotionalTone(), context.humorExcitement(), context.narrativeSignificance(), null, null,
                    "unknown", context.evidence());
            if (sourceItems.putIfAbsent(context.contextId(), contextSource) != null) throw new LyricsGenerationFailure(409, "lyrics_request_invalid");
        }
        for (var context : interpretation.transcriptContexts()) {
            var refs = source.references().getOrDefault(MomentAnalysisSnapshotService.CLIP_TRANSCRIPT_CONTEXT_REFERENCE_KEY, List.of());
            var fullEvidence = refs.stream().map(ref -> new MomentInterpretationResponse.EvidenceCitation(ref.id(), ref.kind(),
                    ref.excerpt(), ref.startSeconds(), ref.endSeconds())).toList();
            var contextSource = new MomentInterpretationResponse.InterpretedMoment(context.contextId(), context.summary(),
                    context.emotionalTone(), context.humorExcitement(), context.narrativeSignificance(), null, null,
                    context.association(), fullEvidence);
            if (sourceItems.putIfAbsent(context.contextId(), contextSource) != null) throw new LyricsGenerationFailure(409, "lyrics_request_invalid");
        }
        String input = providerInput(interpretation, source, source.response().association(), source.response().chatCorrelationExplanation());
        LyricsGenerationProvider.Draft draft;
        try { draft=provider.generate(input,request.genre(),request.mood(),request.energy()); }
        catch (LyricsGenerationFailure e) { throw e; }
        if (!broadcasterProvider.isCurrentConnection(scope)
                || snapshots.getInterpretation(scope,clipId,request.analysisId(),request.interpretationId())==null)
            throw new LyricsGenerationFailure(409,"interpretation_snapshot_stale");
        ClipLyricsResponse response=normalize(clipId,request,interpretation,sourceItems,draft,source.response());
        if (!broadcasterProvider.isCurrentConnection(scope)
                || snapshots.getInterpretation(scope,clipId,request.analysisId(),request.interpretationId())==null)
            throw new LyricsGenerationFailure(409,"interpretation_snapshot_stale");
        snapshots.storeLyrics(scope,response,stored.expiresAt());
        if (!broadcasterProvider.isCurrentConnection(scope)
                || snapshots.getLyrics(scope,clipId,response.lyricsId())==null)
            throw new LyricsGenerationFailure(409,"interpretation_snapshot_stale");
        return response;
    }
    private String providerInput(MomentInterpretationResponse response, MomentAnalysisSnapshotService.Snapshot source,
            String association, String timing) {
        List<Map<String,Object>> ms=new ArrayList<>();
        for(var m:response.moments()) {
            List<Map<String,Object>> ev=m.evidence().stream().<Map<String,Object>>map(e->{var v=new java.util.LinkedHashMap<String,Object>();v.put("id",e.id());v.put("kind",e.kind());v.put("excerpt",e.excerpt());v.put("startSeconds",e.startSeconds());v.put("endSeconds",e.endSeconds());return v;}).toList();
            var value=new java.util.LinkedHashMap<String,Object>(); value.put("id",m.momentId());value.put("summary",m.summary());value.put("emotionalTone",m.emotionalTone());value.put("humorExcitement",m.humorExcitement());value.put("narrativeSignificance",m.narrativeSignificance());value.put("startSeconds",m.startSeconds());value.put("endSeconds",m.endSeconds());value.put("association",m.association()==null?"unknown":m.association());value.put("evidence",ev);ms.add(value);
        }
        List<Map<String,Object>> contexts=new ArrayList<>();
        for(var context:response.sessionContexts()) {
            List<Map<String,Object>> evidence=context.evidence().stream().<Map<String,Object>>map(e->{var v=new java.util.LinkedHashMap<String,Object>();v.put("id",e.id());v.put("kind",e.kind());v.put("excerpt",e.excerpt());v.put("startSeconds",null);v.put("endSeconds",null);return v;}).toList();
            var value=new java.util.LinkedHashMap<String,Object>();value.put("id",context.contextId());value.put("kind","session_context");value.put("summary",context.summary());value.put("emotionalTone",context.emotionalTone());value.put("humorExcitement",context.humorExcitement());value.put("narrativeSignificance",context.narrativeSignificance());value.put("association","unknown");value.put("startSeconds",null);value.put("endSeconds",null);value.put("evidence",evidence);contexts.add(value);
        }
        List<Map<String,Object>> transcriptContexts=new ArrayList<>();
        for(var context:response.transcriptContexts()) {
            List<Map<String,Object>> evidence=source.references().getOrDefault(MomentAnalysisSnapshotService.CLIP_TRANSCRIPT_CONTEXT_REFERENCE_KEY,List.of()).stream().<Map<String,Object>>map(ref->{
                var v=new java.util.LinkedHashMap<String,Object>();v.put("id",ref.id());v.put("kind",ref.kind());v.put("excerpt",ref.excerpt());v.put("startSeconds",ref.startSeconds());v.put("endSeconds",ref.endSeconds());return v;
            }).toList();
            var value=new java.util.LinkedHashMap<String,Object>();value.put("id",context.contextId());value.put("kind","clip_context");value.put("summary",context.summary());value.put("association",context.association());value.put("evidence",evidence);transcriptContexts.add(value);
        }
        try { return new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("association",association==null?"unknown":association,"timingContext",timing==null?"unknown":timing,"moments",ms,"sessionContexts",contexts,"transcriptContexts",transcriptContexts)); }
        catch(Exception e){throw new LyricsGenerationFailure(502,"lyrics_request_invalid");}
    }
    private ClipLyricsResponse normalize(String clipId,LyricsRequest req,MomentInterpretationResponse response,
            Map<String,MomentInterpretationResponse.InterpretedMoment> moments,LyricsGenerationProvider.Draft draft,
            com.stream2song.backend.dto.MemorableMomentsResponse extraction) {
        if(draft==null || draft.title()==null || draft.title().isBlank() || draft.title().length()>100 || draft.sections()==null || draft.sections().size()<3 || draft.sections().size()>8)
            throw invalid();
        int verses=0, choruses=0, bridges=0, total=0; boolean bridgeSeen=false, chorusAfterBridge=false;
        List<ClipLyricsResponse.Section> sections=new ArrayList<>();
        List<List<String>> sourceLines=new ArrayList<>();
        List<String> allLines=new ArrayList<>();
        for(var im:response.moments()) for(var ev:im.evidence()) sourceLines.add(normalizeWords(ev.excerpt()));
        for(var context:response.sessionContexts()) for(var ev:context.evidence()) sourceLines.add(normalizeWords(ev.excerpt()));
        var fullTranscriptContext = moments.get(MomentAnalysisSnapshotService.CLIP_TRANSCRIPT_CONTEXT_REFERENCE_KEY);
        if (fullTranscriptContext != null) addTranscriptContextSourceLines(sourceLines, fullTranscriptContext.evidence(), extraction.transcriptContext().sourceType());
        for(var s:draft.sections()) {
            if(s==null || s.kind()==null || !Set.of("verse","chorus","bridge").contains(s.kind()) || s.label()==null || s.label().isBlank() || s.label().length()>40 || s.lines()==null || s.lines().size()<2 || s.lines().size()>8) throw invalid();
            if(bridgeSeen && !"chorus".equals(s.kind())) throw invalid();
            if("verse".equals(s.kind())) { if(bridgeSeen) throw invalid(); verses++; }
            if("chorus".equals(s.kind())) { choruses++; if(bridgeSeen) chorusAfterBridge=true; }
            if("bridge".equals(s.kind())) { bridges++; if(choruses==0 || verses<2 || bridgeSeen) throw invalid(); bridgeSeen=true; }
            if(bridges>1 || verses>5 || choruses>4 || s.sourceMomentIds()==null || s.sourceMomentIds().isEmpty() || s.sourceMomentIds().size()>10 || s.sourceEvidenceIds()==null || s.sourceEvidenceIds().isEmpty() || s.sourceEvidenceIds().size()>20) throw invalid();
            Set<String> mids=new HashSet<>(s.sourceMomentIds()), eids=new HashSet<>(s.sourceEvidenceIds());
            if(mids.size()!=s.sourceMomentIds().size() || eids.size()!=s.sourceEvidenceIds().size()) throw invalid();
            if(mids.contains(null) || eids.contains(null)) throw invalid();
            Map<String,List<MomentInterpretationResponse.EvidenceCitation>> cited=new java.util.LinkedHashMap<>();
            for(String mid:mids) { var m=moments.get(mid); if(m==null) throw invalid(); cited.put(mid,new ArrayList<>()); }
            for(String eid:eids) {
                boolean found=false;
                for(String mid:mids) { var m=moments.get(mid); for(var ev:m.evidence()) if(ev.id().equals(eid)) { cited.get(mid).add(ev); found=true; } }
                if(!found) throw invalid();
            }
            List<ClipLyricsResponse.SourceMoment> refs=new ArrayList<>();
            for(var entry:cited.entrySet()) { if(entry.getValue().isEmpty()) throw invalid(); var m=moments.get(entry.getKey()); refs.add(new ClipLyricsResponse.SourceMoment(m.momentId(),m.startSeconds(),m.endSeconds(),m.association(),List.copyOf(entry.getValue()))); }
            List<String> lines=new ArrayList<>();
            for(String line:s.lines()) { if(line==null || line.isBlank() || line.length()>160) throw invalid(); total+=line.length(); if(total>5000) throw invalid(); lines.add(line); allLines.add(line); }
            sections.add(new ClipLyricsResponse.Section(s.kind(),s.label(),List.copyOf(lines),List.copyOf(refs)));
        }
        if(verses<2 || choruses<1 || bridges>1 || !"verse".equals(sections.get(0).kind()) || (bridges==1 && !chorusAfterBridge)) throw invalid();
        if(hasLongSourceOverlap(String.join(" ",allLines),sourceLines)) throw new LyricsGenerationFailure(422,"lyrics_source_overlap_rejected");
        return new ClipLyricsResponse(UUID.randomUUID().toString(),req.analysisId(),req.interpretationId(),clipId,req.genre(),req.mood(),req.energy(),true,true,
                extraction.association(),extraction.chatTimingBasis(),extraction.chatCorrelationExplanation(),draft.title(),List.copyOf(sections));
    }
    private boolean hasLongSourceOverlap(String line,List<List<String>> sources) { List<String> w=normalizeWords(line); for(int i=0;i+12<=w.size();i++) for(List<String> src:sources) for(int j=0;j+12<=src.size();j++) if(w.subList(i,i+12).equals(src.subList(j,j+12))) return true; return false; }
    private void addTranscriptContextSourceLines(List<List<String>> sources,
            List<MomentInterpretationResponse.EvidenceCitation> evidence, String sourceType) {
        List<String> orderedChunks = evidence.stream().map(MomentInterpretationResponse.EvidenceCitation::excerpt)
                .filter(java.util.Objects::nonNull).toList();
        for (String chunk : orderedChunks) sources.add(normalizeWords(chunk));
        if ("full_text_fallback".equals(sourceType)) {
            sources.add(normalizeWords(String.join("", orderedChunks)));
            return;
        }
        List<String> chain = new ArrayList<>();
        MomentInterpretationResponse.EvidenceCitation previous = null;
        for (var item : evidence) {
            boolean adjacent = previous != null && previous.endSeconds() != null && item.startSeconds() != null
                    && previous.startSeconds() != null && item.startSeconds() >= previous.startSeconds()
                    && item.startSeconds() - previous.endSeconds() <= 1.0;
            if (!adjacent && !chain.isEmpty()) {
                sources.add(normalizeWords(String.join(" ", chain)));
                chain.clear();
            }
            if (item.excerpt() != null) chain.add(item.excerpt());
            previous = item;
        }
        if (!chain.isEmpty()) sources.add(normalizeWords(String.join(" ", chain)));
    }
    private List<String> normalizeWords(String text) { return List.of(Normalizer.normalize(text.toLowerCase(Locale.ROOT),Normalizer.Form.NFKC).split(WORDS.pattern())).stream().filter(s->!s.isBlank()).toList(); }
    private boolean validId(String s){return s!=null&&s.matches("[A-Za-z0-9_-]{1,100}");}
    private LyricsGenerationFailure invalid(){return new LyricsGenerationFailure(502,"lyrics_output_invalid");}
    public record LyricsRequest(String analysisId,String interpretationId,String genre,String mood,String energy) { }
}
