package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.stream2song.backend.dto.MemorableMomentsResponse;
import com.stream2song.backend.dto.MomentInterpretationResponse;
import com.stream2song.backend.dto.MomentInterpretationResponse.EvidenceCitation;
import com.stream2song.backend.dto.MomentInterpretationResponse.InterpretedMoment;
import com.stream2song.backend.dto.MomentInterpretationResponse.InterpretedContext;
import com.stream2song.backend.dto.MomentInterpretationResponse.InterpretedClipContext;

class ClipLyricsGenerationServiceTest {
    @Test void contextOnlyLyricsReceiveAndCiteTheFullStoredTranscript() {
        var snapshots=new MomentAnalysisSnapshotService(); var scope=new TwitchBroadcasterProvider.ConnectionScope("owner",4);
        long revision=snapshots.revision(scope,"clip-transcript");
        List<String> lines=List.of("bullshit application","how can I","checking","break everything","start","not know world","maybe we can solve this");
        var utterances=java.util.stream.IntStream.range(0,lines.size()).mapToObj(i->new MemorableMomentsResponse.TranscriptUtterance(
                "clip-transcript-"+(i+1),lines.get(i),(double)i,i+0.5)).toList();
        var extraction=new MemorableMomentsResponse("a-transcript","clip-transcript",new MemorableMomentsResponse.SourceClip("t","c",30,null,null),
                "unknown","unaligned",null,"timing unknown",true,"0-100","no score cues",List.of(),
                MemorableMomentsResponse.SessionChatContext.empty(),new MemorableMomentsResponse.TranscriptContext(lines.size(),lines.size(),lines.size(),0,lines.stream().mapToInt(String::length).sum(),0,"unknown","utterances",utterances));
        snapshots.store(scope,extraction,revision);
        List<MomentInterpretationResponse.EvidenceCitation> citations=java.util.stream.IntStream.range(0,lines.size())
                .mapToObj(i->new MomentInterpretationResponse.EvidenceCitation("clip-transcript-"+(i+1),"clip_transcript_context",lines.get(i),(double)i,i+0.5)).toList();
        var interpretation=new MomentInterpretationResponse("i-transcript","a-transcript","clip-transcript",true,true,List.of(),List.of(),List.of(
                new InterpretedClipContext(MomentAnalysisSnapshotService.CLIP_TRANSCRIPT_CONTEXT_REFERENCE_KEY,
                        "A speaker is dealing with a broken application and uncertainty.","frustrated","low","A problem-solving thought shifts into uncertainty.",null,null,"unknown",citations.subList(0,1))));
        snapshots.storeInterpretation(scope,"clip-transcript","a-transcript",interpretation);
        var draft=new AtomicReference<LyricsGenerationProvider.Draft>(new LyricsGenerationProvider.Draft("Debugging",List.of(
                transcriptSection("verse","Verse 1"),transcriptSection("chorus","Chorus"),transcriptSection("verse","Verse 2"))));
        var captured=new AtomicReference<String>();
        LyricsGenerationProvider provider=new LyricsGenerationProvider(){ public boolean isConfigured(){return true;} public Draft generate(String input,String genre,String mood,String energy){captured.set(input);return draft.get();} };
        TwitchBroadcasterProvider broadcaster=new TwitchBroadcasterProvider(){public String connectedBroadcasterId(){return "owner";} public ConnectionScope connectionScope(){return scope;}};
        var result=new ClipLyricsGenerationService(broadcaster,snapshots,provider)
                .generate("clip-transcript",new ClipLyricsGenerationService.LyricsRequest("a-transcript","i-transcript","pop","playful","medium"));
        for(String line:lines) assertTrue(captured.get().contains(line),"lyrics provider must receive full bounded transcript context: "+line);
        assertTrue(captured.get().contains("\"kind\":\"clip_context\""));
        assertTrue(captured.get().contains("\"startSeconds\":4.0"));
        assertEquals(lines,result.sections().getFirst().sources().getFirst().evidence().stream().map(MomentInterpretationResponse.EvidenceCitation::excerpt).toList());
        assertEquals("clip-transcript-context",result.sections().getFirst().sources().getFirst().momentId());
        assertTrue(result.sections().getFirst().sources().getFirst().evidence().stream().allMatch(item->"clip_transcript_context".equals(item.kind())));
        String quoteOne="bullshit application how can I checking";
        String quoteTwo="break everything start not know world maybe we can solve this";
        var quoteSection=new LyricsGenerationProvider.SectionDraft("verse","Verse",List.of(quoteOne,quoteTwo),
                List.of("clip-transcript-context"),java.util.stream.IntStream.range(0,lines.size()).mapToObj(i->"clip-transcript-"+(i+1)).toList());
        draft.set(new LyricsGenerationProvider.Draft("Copied transcript",List.of(quoteSection,
                new LyricsGenerationProvider.SectionDraft("chorus","Chorus",List.of("A fresh chorus line","Another new chorus line"),List.of("clip-transcript-context"),java.util.stream.IntStream.range(0,lines.size()).mapToObj(i->"clip-transcript-"+(i+1)).toList()),
                new LyricsGenerationProvider.SectionDraft("verse","Verse 2",List.of("A new second verse","No borrowed phrase here"),List.of("clip-transcript-context"),java.util.stream.IntStream.range(0,lines.size()).mapToObj(i->"clip-transcript-"+(i+1)).toList()))));
        var overlap=assertThrows(LyricsGenerationFailure.class,()->new ClipLyricsGenerationService(broadcaster,snapshots,provider)
                .generate("clip-transcript",new ClipLyricsGenerationService.LyricsRequest("a-transcript","i-transcript","pop","playful","medium")));
        assertEquals("lyrics_source_overlap_rejected",overlap.reason());
    }
    @Test void contextOnlyLyricsKeepUntimedSessionChatProvenance() {
        var snapshots=new MomentAnalysisSnapshotService(); var scope=new TwitchBroadcasterProvider.ConnectionScope("owner",4);
        long revision=snapshots.revision(scope,"clip");
        var contextMessage=new MemorableMomentsResponse.SessionChatMessage("session-chat-1","that was so funny");
        var extraction=new MemorableMomentsResponse("a-context","clip",new MemorableMomentsResponse.SourceClip("t","c",30,null,null),
                "unknown","unaligned",null,"No reliable clip timing; background only.",true,"0-100",null,List.of(),
                new MemorableMomentsResponse.SessionChatContext(12,12,1,11,List.of(contextMessage),"unknown"));
        snapshots.store(scope,extraction,revision);
        var evidence=new EvidenceCitation("session-chat-1","session_chat_context","that was so funny",null,null);
        var interpretation=new MomentInterpretationResponse("i-context","a-context","clip",true,true,List.of(),List.of(
                new InterpretedContext("session-chat-context","Recent chat was playful","playful","some humor","background flavor","unknown",List.of(evidence))));
        snapshots.storeInterpretation(scope,"clip","a-context",interpretation);
        var draft=new LyricsGenerationProvider.Draft("Chat Flavor",List.of(
                contextSection("verse","Verse 1"),contextSection("chorus","Chorus"),contextSection("verse","Verse 2")));
        var captured=new AtomicReference<String>();
        LyricsGenerationProvider provider=new LyricsGenerationProvider(){ public boolean isConfigured(){return true;} public Draft generate(String input,String genre,String mood,String energy){captured.set(input);return draft;} };
        TwitchBroadcasterProvider broadcaster=new TwitchBroadcasterProvider(){public String connectedBroadcasterId(){return "owner";} public ConnectionScope connectionScope(){return scope;}};
        var result=new ClipLyricsGenerationService(broadcaster,snapshots,provider)
                .generate("clip",new ClipLyricsGenerationService.LyricsRequest("a-context","i-context","pop","playful","medium"));
        assertTrue(captured.get().contains("\"kind\":\"session_context\""));
        assertTrue(captured.get().contains("\"startSeconds\":null"));
        assertTrue(captured.get().contains("\"association\":\"unknown\""));
        var source=result.sections().getFirst().sources().getFirst();
        assertEquals("session-chat-context",source.momentId());
        assertNull(source.startSeconds()); assertNull(source.endSeconds()); assertEquals("unknown",source.association());
        assertEquals("session_chat_context",source.evidence().getFirst().kind());
        assertNull(source.evidence().getFirst().startSeconds()); assertNull(source.evidence().getFirst().endSeconds());
    }
    @Test void mapsProviderCitationsToServerEvidenceAndPreservesSourceTimes() {
        var snapshots=new MomentAnalysisSnapshotService(); var scope=new TwitchBroadcasterProvider.ConnectionScope("owner",4);
        long revision=snapshots.revision(scope,"clip");
        var moment=new MemorableMomentsResponse.Moment("m1","reaction",12.0,14.0,70,List.of(),List.of(),"clip","unknown","heuristic",List.of());
        var extraction=new MemorableMomentsResponse("a1","clip",new MemorableMomentsResponse.SourceClip("t","c",30,null,null),"unknown","unaligned",null,"unknown association",true,"0-100",null,List.of(moment));
        snapshots.store(scope,extraction,revision);
        String sourcePhrase="we have all come together in this place to make a bright and beautiful song";
        var citation=new EvidenceCitation("m1-t0","transcript",sourcePhrase,12.0,13.0);
        var interpretation=new MomentInterpretationResponse("i1","a1","clip",true,true,List.of(new InterpretedMoment("m1","Meaning uncertain","excited","some excitement","possible turn",12.0,14.0,"unknown",List.of(citation))));
        snapshots.storeInterpretation(scope,"clip","a1",interpretation);
        var draft=new AtomicReference<LyricsGenerationProvider.Draft>(new LyricsGenerationProvider.Draft("Bright Loop",List.of(section("verse","Verse 1"),section("chorus","Chorus"),section("verse","Verse 2"))));
        var styles=new ArrayList<String>();
        LyricsGenerationProvider provider=new LyricsGenerationProvider(){ public boolean isConfigured(){return true;} public Draft generate(String input,String genre,String mood,String energy){assertTrue(input.contains("unknown association"));styles.add(genre+":"+mood+":"+energy);return draft.get();} };
        TwitchBroadcasterProvider broadcaster=new TwitchBroadcasterProvider(){public String connectedBroadcasterId(){return "owner";} public ConnectionScope connectionScope(){return scope;}};
        var service=new ClipLyricsGenerationService(broadcaster,snapshots,provider);
        var result=service.generate("clip",new ClipLyricsGenerationService.LyricsRequest("a1","i1","pop","playful","medium"));
        assertEquals(12.0,result.sections().getFirst().sources().getFirst().startSeconds());
        assertEquals("m1-t0",result.sections().getFirst().sources().getFirst().evidence().getFirst().id());
        assertThrows(LyricsGenerationFailure.class,()->service.generate("clip",new ClipLyricsGenerationService.LyricsRequest("a1","i1","artist-name","playful","medium")));
        service.generate("clip",new ClipLyricsGenerationService.LyricsRequest("a1","i1",null,null,null));
        assertEquals("pop:playful:medium",styles.getLast());
        draft.set(new LyricsGenerationProvider.Draft("Bad",List.of(new LyricsGenerationProvider.SectionDraft(null,"Verse",List.of("one","two"),List.of("m1"),List.of("m1-t0")),section("chorus","Chorus"),section("verse","Verse 2"))));
        var malformed=assertThrows(LyricsGenerationFailure.class,()->service.generate("clip",new ClipLyricsGenerationService.LyricsRequest("a1","i1","pop","playful","medium")));
        assertEquals("lyrics_output_invalid",malformed.reason());
        draft.set(new LyricsGenerationProvider.Draft("Bad refs",List.of(new LyricsGenerationProvider.SectionDraft("verse","Verse",List.of("one","two"),List.of("m1"),java.util.Arrays.asList((String)null)),section("chorus","Chorus"),section("verse","Verse 2"))));
        assertEquals("lyrics_output_invalid",assertThrows(LyricsGenerationFailure.class,()->service.generate("clip",new ClipLyricsGenerationService.LyricsRequest("a1","i1","pop","playful","medium"))).reason());
        draft.set(new LyricsGenerationProvider.Draft("Quoted",List.of(
                new LyricsGenerationProvider.SectionDraft("verse","Verse 1",List.of("we have all come together in this place","to make a bright and beautiful song"),List.of("m1"),List.of("m1-t0")),section("chorus","Chorus"),section("verse","Verse 2"))));
        assertEquals("lyrics_source_overlap_rejected",assertThrows(LyricsGenerationFailure.class,()->service.generate("clip",new ClipLyricsGenerationService.LyricsRequest("a1","i1","pop","playful","medium"))).reason());
        draft.set(new LyricsGenerationProvider.Draft("Bad bridge order",List.of(section("verse","Verse 1"),section("chorus","Chorus"),section("verse","Verse 2"),section("bridge","Bridge"),section("verse","Verse 3"))));
        assertEquals("lyrics_output_invalid",assertThrows(LyricsGenerationFailure.class,()->service.generate("clip",new ClipLyricsGenerationService.LyricsRequest("a1","i1","pop","playful","medium"))).reason());
    }
    private LyricsGenerationProvider.SectionDraft section(String kind,String label){return new LyricsGenerationProvider.SectionDraft(kind,label,List.of("A new bright line","Another different line"),List.of("m1"),List.of("m1-t0"));}
    private LyricsGenerationProvider.SectionDraft contextSection(String kind,String label){return new LyricsGenerationProvider.SectionDraft(kind,label,List.of("A playful chat refrain","A little moment in the night"),List.of("session-chat-context"),List.of("session-chat-1"));}
    private LyricsGenerationProvider.SectionDraft transcriptSection(String kind,String label){return new LyricsGenerationProvider.SectionDraft(kind,label,List.of("Checking the wires in a world that won't hold","Trying to begin as the pieces unfold"),List.of("clip-transcript-context"),java.util.stream.IntStream.range(0,7).mapToObj(i->"clip-transcript-"+(i+1)).toList());}
}
