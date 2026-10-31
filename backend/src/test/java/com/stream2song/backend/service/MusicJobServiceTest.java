package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.stream2song.backend.dto.ClipLyricsResponse;
import com.stream2song.backend.dto.MemorableMomentsResponse;
import com.stream2song.backend.dto.MomentInterpretationResponse;

class MusicJobServiceTest {
    @Test void repeatedCreateIsIdempotentAndRetryRequiresExpectedAttempt() throws Exception {
        Fixture f=fixture();AtomicInteger submissions=new AtomicInteger();CountDownLatch calls=new CountDownLatch(2);
        MusicGenerationProvider provider=new MusicGenerationProvider(){public boolean isConfigured(){return true;}public String fingerprint(){return "mock:v1";}
            public Prediction submit(String lyrics,String prompt){int call=submissions.incrementAndGet();calls.countDown();if(call==1)throw new MusicGenerationFailure(503,"music_provider_rate_limited",false);return new Prediction("private-provider-id","processing",null);}
            public Prediction poll(String id){return new Prediction(id,"processing",null);}public byte[] download(URI uri){return new byte[0];}};
        var jobs=new MusicJobService(f.broadcaster,f.snapshots,provider);
        try{
            var first=jobs.create("clip-1","lyrics-1");var duplicate=jobs.create("clip-1","lyrics-1");
            assertEquals(first.jobId(),duplicate.jobId());
            awaitStatus(jobs,first.jobId(),"FAILED");var failed=jobs.get(first.jobId());assertTrue(failed.retryable());assertEquals("GENERATION",failed.retryMode());var retried=jobs.retry(first.jobId(),1);assertEquals(2,retried.attempt());assertTrue(calls.await(2,TimeUnit.SECONDS));
            assertThrows(MusicGenerationFailure.class,()->jobs.retry(first.jobId(),1));
            assertTrue(submissions.get()<=2);
        }finally{jobs.shutdown();}
    }

    @Test void permanentSubmitRejectionDoesNotOfferRetryOrAcceptAnInvalidRetryMode() throws Exception {
        Fixture f=fixture();MusicGenerationProvider provider=new MusicGenerationProvider(){public boolean isConfigured(){return true;}public String fingerprint(){return "mock:v1";}
            public Prediction submit(String lyrics,String prompt){throw new MusicGenerationFailure(502,"music_provider_rejected",false);}
            public Prediction poll(String id){throw new AssertionError();}public byte[] download(URI uri){throw new AssertionError();}};
        var jobs=new MusicJobService(f.broadcaster,f.snapshots,provider);
        try{var job=jobs.create("clip-1","lyrics-1");awaitStatus(jobs,job.jobId(),"FAILED");var failed=jobs.get(job.jobId());assertFalse(failed.retryable());assertNull(failed.retryMode());assertThrows(MusicGenerationFailure.class,()->jobs.retry(job.jobId(),1));}
        finally{jobs.shutdown();}
    }

    @Test void staleLyricsAreRejectedBeforePaidSubmissionAndScopeGenerationOwnsJob() {
        Fixture f=fixture();AtomicInteger submissions=new AtomicInteger();MusicGenerationProvider provider=new MusicGenerationProvider(){public boolean isConfigured(){return true;}public String fingerprint(){return "mock:v1";}public Prediction submit(String l,String p){submissions.incrementAndGet();return new Prediction("p","processing",null);}public Prediction poll(String id){return new Prediction(id,"processing",null);}public byte[] download(URI u){return new byte[0];}};
        var jobs=new MusicJobService(f.broadcaster,f.snapshots,provider);
        try{
            f.snapshots.invalidate("owner","clip-1");assertThrows(MusicGenerationFailure.class,()->jobs.create("clip-1","lyrics-1"));assertEquals(0,submissions.get());
        }finally{jobs.shutdown();}
    }

    @Test void taggedLyricsOverModelLimitFailBeforeJobReservationOrProviderSubmission(){
        Fixture f=fixture();var citation=new MomentInterpretationResponse.EvidenceCitation("m1-t0","transcript","hello there",1.0,2.0);var source=new ClipLyricsResponse.SourceMoment("m1",1.0,2.0,"unknown",List.of(citation));
        List<String> longLines=java.util.Collections.nCopies(8,"x".repeat(155));List<ClipLyricsResponse.Section> sections=List.of(
                new ClipLyricsResponse.Section("verse","v",longLines,List.of(source)),new ClipLyricsResponse.Section("chorus","c",longLines,List.of(source)),new ClipLyricsResponse.Section("verse","v2",longLines,List.of(source)));
        var longLyrics=new ClipLyricsResponse("lyrics-long","analysis-1","interpretation-1","clip-1","pop","playful","medium",true,true,"unknown",null,"context","Long",sections);
        f.snapshots.storeLyrics(f.scope,longLyrics,java.time.Instant.now().plusSeconds(600));AtomicInteger submissions=new AtomicInteger();
        MusicGenerationProvider provider=new MusicGenerationProvider(){public boolean isConfigured(){return true;}public String fingerprint(){return "mock";}public Prediction submit(String l,String p){submissions.incrementAndGet();return new Prediction("p","processing",null);}public Prediction poll(String id){return new Prediction(id,"processing",null);}public byte[] download(URI uri){return new byte[0];}};
        var jobs=new MusicJobService(f.broadcaster,f.snapshots,provider);
        try{var error=assertThrows(MusicGenerationFailure.class,()->jobs.create("clip-1","lyrics-long"));assertEquals("music_lyrics_too_long",error.reason());assertEquals(0,submissions.get());}
        finally{jobs.shutdown();}
    }

    @Test void paidGenerationRetriesStopAfterThreeAttempts() throws Exception {
        Fixture f=fixture();AtomicInteger submissions=new AtomicInteger();MusicGenerationProvider provider=new MusicGenerationProvider(){public boolean isConfigured(){return true;}public String fingerprint(){return "mock";}public Prediction submit(String l,String p){submissions.incrementAndGet();return new Prediction("p","processing",null);}public Prediction poll(String id){return new Prediction(id,"failed",null);}public byte[] download(URI uri){return new byte[0];}};
        var jobs=new MusicJobService(f.broadcaster,f.snapshots,provider,java.time.Clock.systemUTC(),duration->{});
        try{var job=jobs.create("clip-1","lyrics-1");awaitStatus(jobs,job.jobId(),"FAILED");for(int attempt=1;attempt<3;attempt++){var status=jobs.get(job.jobId());assertTrue(status.retryable());jobs.retry(job.jobId(),status.attempt());awaitStatus(jobs,job.jobId(),"FAILED");}var terminal=jobs.get(job.jobId());assertEquals(3,terminal.attempt());assertFalse(terminal.retryable());assertEquals(3,submissions.get());assertThrows(MusicGenerationFailure.class,()->jobs.retry(job.jobId(),3));}
        finally{jobs.shutdown();}
    }

    @Test void ambiguousSubmitIsNeverRetriedThroughPaidRetryEndpoint() throws Exception {
        Fixture f=fixture();AtomicInteger submissions=new AtomicInteger();MusicGenerationProvider provider=new MusicGenerationProvider(){public boolean isConfigured(){return true;}public String fingerprint(){return "mock:v1";}public Prediction submit(String l,String p){submissions.incrementAndGet();throw new MusicGenerationFailure(504,"music_submission_indeterminate",true);}public Prediction poll(String id){throw new AssertionError();}public byte[] download(URI uri){throw new AssertionError();}};
        var jobs=new MusicJobService(f.broadcaster,f.snapshots,provider);
        try{var job=jobs.create("clip-1","lyrics-1");awaitStatus(jobs,job.jobId(),"FAILED");assertFalse(jobs.get(job.jobId()).retryable());assertThrows(MusicGenerationFailure.class,()->jobs.retry(job.jobId(),1));assertEquals(1,submissions.get());}
        finally{jobs.shutdown();}
    }

    @Test void statusRecoveryUsesSavedProviderIdWithoutAnotherPaidSubmit() throws Exception {
        Fixture f=fixture();AtomicInteger submissions=new AtomicInteger(),polls=new AtomicInteger();
        MusicGenerationProvider provider=new MusicGenerationProvider(){
            public boolean isConfigured(){return true;}public String fingerprint(){return "mock:v1";}
            public Prediction submit(String lyrics,String prompt){submissions.incrementAndGet();return new Prediction("private-provider-id","processing",null);}
            public Prediction poll(String id){assertEquals("private-provider-id",id);if(polls.incrementAndGet()<=6)throw new MusicGenerationFailure(503,"music_provider_poll_retry",false);return new Prediction(id,"succeeded",URI.create("https://a.replicate.delivery/audio.mp3"));}
            public byte[] download(URI uri){return new byte[]{'I','D','3',1};}
        };
        var jobs=new MusicJobService(f.broadcaster,f.snapshots,provider,java.time.Clock.systemUTC(),duration->{});
        try{
            var created=jobs.create("clip-1","lyrics-1");awaitStatus(jobs,created.jobId(),"FAILED");
            var statusFailure=jobs.get(created.jobId());assertEquals("STATUS",statusFailure.retryMode());assertTrue(statusFailure.retryable());
            jobs.retry(created.jobId(),statusFailure.attempt());awaitStatus(jobs,created.jobId(),"COMPLETED");
            assertEquals(1,submissions.get());assertNotNull(jobs.audio(created.jobId()));
        }finally{jobs.shutdown();}
    }

    @Test void queuedJobExpiresBeforeSubmitAndLateAcceptedWorkCannotPublishIntoExpiredRecord() throws Exception {
        Fixture f=fixture();addLyricsFixture(f,"clip-2","lyrics-2");addLyricsFixture(f,"clip-3","lyrics-3");
        AtomicInteger submissions=new AtomicInteger();CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        MusicGenerationProvider provider=new MusicGenerationProvider(){public boolean isConfigured(){return true;}public String fingerprint(){return "mock:v1";}
            public Prediction submit(String lyrics,String prompt){submissions.incrementAndGet();entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return new Prediction("accepted-id","processing",null);}
            public Prediction poll(String id){throw new AssertionError("expired attempt must not poll");}public byte[] download(URI uri){throw new AssertionError();}};
        MutableClock clock=new MutableClock(java.time.Instant.now());var jobs=new MusicJobService(f.broadcaster,f.snapshots,provider,clock,duration->{});
        try{
            var one=jobs.create("clip-1","lyrics-1");var two=jobs.create("clip-2","lyrics-2");var three=jobs.create("clip-3","lyrics-3");
            assertTrue(entered.await(2,TimeUnit.SECONDS));clock.advance(java.time.Duration.ofMinutes(16));jobs.cleanup();
            assertEquals("EXPIRED",jobs.get(three.jobId()).status());release.countDown();
            awaitStatus(jobs,one.jobId(),"FAILED");awaitStatus(jobs,two.jobId(),"FAILED");
            assertEquals(2,submissions.get());assertEquals("EXPIRED",jobs.get(three.jobId()).status());
        }finally{release.countDown();jobs.shutdown();}
    }

    private void awaitStatus(MusicJobService jobs,String id,String status)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(System.nanoTime()<deadline){if(status.equals(jobs.get(id).status()))return;Thread.sleep(10);}fail("job did not reach "+status);}
    private Fixture fixture(){
        var snapshots=new MomentAnalysisSnapshotService();var scope=new TwitchBroadcasterProvider.ConnectionScope("owner",9);
        var moment=new MemorableMomentsResponse.Moment("m1","moment",1.0,2.0,50,List.of(new MemorableMomentsResponse.TranscriptEvidence("hello there",1.0,2.0)),List.of(),"clip-1","unknown","heuristic",List.of());
        var extraction=new MemorableMomentsResponse("analysis-1","clip-1",new MemorableMomentsResponse.SourceClip("title","creator",20,null,null),"unknown","unaligned",null,"Unknown association",true,"0-100",null,List.of(moment));
        snapshots.store(scope,extraction,snapshots.revision(scope,"clip-1"));
        var citation=new MomentInterpretationResponse.EvidenceCitation("m1-t0","transcript","hello there",1.0,2.0);
        var interpreted=new MomentInterpretationResponse("interpretation-1","analysis-1","clip-1",true,true,List.of(new MomentInterpretationResponse.InterpretedMoment("m1","summary","tone","energy","significance",1.0,2.0,"unknown",List.of(citation))));
        snapshots.storeInterpretation(scope,"clip-1","analysis-1",interpreted);
        var source=new ClipLyricsResponse.SourceMoment("m1",1.0,2.0,"unknown",List.of(citation));
        var lyrics=new ClipLyricsResponse("lyrics-1","analysis-1","interpretation-1","clip-1","pop","playful","medium",true,true,"unknown",null,"Unknown association","Song",List.of(new ClipLyricsResponse.Section("verse","Verse 1",List.of("line one","line two"),List.of(source)),new ClipLyricsResponse.Section("chorus","Chorus",List.of("line one","line two"),List.of(source)),new ClipLyricsResponse.Section("verse","Verse 2",List.of("line one","line two"),List.of(source))));
        snapshots.storeLyrics(scope,lyrics,java.time.Instant.now().plusSeconds(600));
        TwitchBroadcasterProvider broadcaster=new TwitchBroadcasterProvider(){public String connectedBroadcasterId(){return "owner";}public ConnectionScope connectionScope(){return scope;}};
        return new Fixture(snapshots,broadcaster,scope);
    }
    private void addLyricsFixture(Fixture f,String clipId,String lyricsId){
        var scope=f.scope;var moment=new MemorableMomentsResponse.Moment("m1","moment",1.0,2.0,50,List.of(new MemorableMomentsResponse.TranscriptEvidence("hello there",1.0,2.0)),List.of(),clipId,"unknown","heuristic",List.of());
        var extraction=new MemorableMomentsResponse("analysis-"+clipId,clipId,new MemorableMomentsResponse.SourceClip("title","creator",20,null,null),"unknown","unaligned",null,"Unknown association",true,"0-100",null,List.of(moment));
        f.snapshots.store(scope,extraction,f.snapshots.revision(scope,clipId));var citation=new MomentInterpretationResponse.EvidenceCitation("m1-t0","transcript","hello there",1.0,2.0);
        f.snapshots.storeInterpretation(scope,clipId,extraction.analysisId(),new MomentInterpretationResponse("interpretation-"+clipId,extraction.analysisId(),clipId,true,true,List.of(new MomentInterpretationResponse.InterpretedMoment("m1","summary","tone","energy","significance",1.0,2.0,"unknown",List.of(citation)))));
        var source=new ClipLyricsResponse.SourceMoment("m1",1.0,2.0,"unknown",List.of(citation));var draft=new ClipLyricsResponse(lyricsId,extraction.analysisId(),"interpretation-"+clipId,clipId,"pop","playful","medium",true,true,"unknown",null,"Unknown association","Song",List.of(new ClipLyricsResponse.Section("verse","Verse 1",List.of("line one","line two"),List.of(source)),new ClipLyricsResponse.Section("chorus","Chorus",List.of("line one","line two"),List.of(source)),new ClipLyricsResponse.Section("verse","Verse 2",List.of("line one","line two"),List.of(source))));
        f.snapshots.storeLyrics(scope,draft,java.time.Instant.now().plusSeconds(600));
    }
    private record Fixture(MomentAnalysisSnapshotService snapshots,TwitchBroadcasterProvider broadcaster,TwitchBroadcasterProvider.ConnectionScope scope){}
    private static final class MutableClock extends java.time.Clock {
        private java.time.Instant instant; MutableClock(java.time.Instant instant){this.instant=instant;}void advance(java.time.Duration duration){instant=instant.plus(duration);}
        public java.time.ZoneId getZone(){return java.time.ZoneId.of("UTC");}public java.time.Clock withZone(java.time.ZoneId zone){return this;}public java.time.Instant instant(){return instant;}
    }
}
