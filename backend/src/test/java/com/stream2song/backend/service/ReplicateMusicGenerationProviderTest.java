package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.stream2song.backend.config.MusicProperties;

class ReplicateMusicGenerationProviderTest {
    private final JsonMapper mapper=JsonMapper.builder().build();
    @Test void sendsOfficialAsyncRequestAndParsesProviderPrediction() throws Exception {
        Fake transport=new Fake(new ReplicateHttpTransport.Response(201,"{\"id\":\"pred-123\",\"status\":\"starting\",\"output\":null}".getBytes(StandardCharsets.UTF_8),"application/json"));
        var provider=new ReplicateMusicGenerationProvider(new MusicProperties(true,"replicate","server-secret"),mapper,transport);
        var prediction=provider.submit("[Verse]\nFirst line\n[Chorus]\nSing","clean pop song");
        assertEquals("pred-123",prediction.id());assertEquals("starting",prediction.status());
        assertEquals("https://api.replicate.com/v1/models/minimax/music-2.5/predictions",transport.uri.toString());
        assertEquals("server-secret",transport.token);assertEquals("15m",transport.headers.get("Cancel-After"));assertFalse(transport.headers.containsKey("Prefer"));
        var input=mapper.readTree(transport.body).path("input");assertEquals("mp3",input.path("audio_format").asText());assertEquals(44100,input.path("sample_rate").asInt());assertTrue(input.path("lyrics").asText().contains("[Verse]"));
        assertFalse(prediction.toString().contains("server-secret"));
    }
    @Test void pollUsesFixedValidatedPathAndDownloadDoesNotSendProviderCredential() {
        Fake poll=new Fake(new ReplicateHttpTransport.Response(200,"{\"id\":\"p_1\",\"status\":\"succeeded\",\"output\":\"https://a.replicate.delivery/song.mp3\"}".getBytes(StandardCharsets.UTF_8),"application/json"));
        var provider=new ReplicateMusicGenerationProvider(new MusicProperties(true,"replicate","secret"),mapper,poll);
        assertEquals("succeeded",provider.poll("p_1").status());assertEquals("https://api.replicate.com/v1/predictions/p_1",poll.uri.toString());
        Fake audio=new Fake(new ReplicateHttpTransport.Response(200,new byte[]{'I','D','3',4,0,0,0,0,0,0,(byte)0xff,(byte)0xfb,(byte)0x90,0x64},"application/octet-stream"));
        provider=new ReplicateMusicGenerationProvider(new MusicProperties(true,"replicate","secret"),mapper,audio);
        assertEquals(14,provider.download(URI.create("https://a.replicate.delivery/song.mp3")).length);assertNull(audio.token);
    }
    @Test void rejectsCdnLookalikesAndMarksAmbiguousSubmitWithoutRetry() {
        var provider=new ReplicateMusicGenerationProvider(new MusicProperties(true,"replicate","secret"),mapper,new Fake(new ReplicateHttpTransport.Response(200,new byte[0],"")));
        assertThrows(MusicGenerationFailure.class,()->provider.download(URI.create("https://replicate.delivery.evil.example/song.mp3")));
        assertThrows(MusicGenerationFailure.class,()->provider.download(URI.create("http://a.replicate.delivery/song.mp3")));
        assertThrows(MusicGenerationFailure.class,()->provider.download(URI.create("https://user@a.replicate.delivery/song.mp3")));
        assertThrows(MusicGenerationFailure.class,()->provider.download(URI.create("https://a.replicate.delivery:444/song.mp3")));
        var badAudio=new ReplicateMusicGenerationProvider(new MusicProperties(true,"replicate","secret"),mapper,new Fake(new ReplicateHttpTransport.Response(200,new byte[]{(byte)0xff,0,(byte)0xff,0},"audio/mpeg")));
        assertEquals("music_output_invalid",assertThrows(MusicGenerationFailure.class,()->badAudio.download(URI.create("https://a.replicate.delivery/a.mp3"))).reason());
        var expired=new ReplicateMusicGenerationProvider(new MusicProperties(true,"replicate","secret"),mapper,new Fake(new ReplicateHttpTransport.Response(403,new byte[0],"text/plain")));
        assertEquals("music_output_expired",assertThrows(MusicGenerationFailure.class,()->expired.download(URI.create("https://a.replicate.delivery/a.mp3"))).reason());
        var broken=new ReplicateMusicGenerationProvider(new MusicProperties(true,"replicate","secret"),mapper,(method,uri,token,body,headers,max,timeout)->{throw new com.stream2song.backend.client.JavaHttpReplicateTransport.TransportFailure(true);});
        var failure=assertThrows(MusicGenerationFailure.class,()->broken.submit("lyrics","prompt"));assertTrue(failure.acceptanceUnknown());
    }
    @Test void classifiesKnownSubmitHttpStatusesWithoutExposingResponseBodies() {
        Map<Integer,String> expected=Map.of(401,"music_provider_auth_failed",403,"music_provider_access_denied",
                402,"music_provider_billing_required",400,"music_provider_input_invalid",422,"music_provider_input_invalid",
                429,"music_provider_rate_limited",404,"music_provider_rejected");
        for(var entry:expected.entrySet()){
            String privateBody="{\"error\":\"dummy-provider-detail\"}";
            var provider=new ReplicateMusicGenerationProvider(new MusicProperties(true,"replicate","fixture-token"),mapper,
                    new Fake(new ReplicateHttpTransport.Response(entry.getKey(),privateBody.getBytes(StandardCharsets.UTF_8),"application/json")));
            MusicGenerationFailure failure=assertThrows(MusicGenerationFailure.class,()->provider.submit("lyrics","prompt"));
            assertEquals(entry.getValue(),failure.reason());assertFalse(failure.acceptanceUnknown());
            assertFalse(failure.getMessage().contains("dummy-provider-detail"));assertFalse(failure.getMessage().contains("fixture-token"));
        }
    }
    private static final class Fake implements ReplicateHttpTransport {
        private final Response response; URI uri; String token; byte[] body; Map<String,String> headers;
        Fake(Response response){this.response=response;}
        public Response exchange(String method,URI uri,String token,byte[] body,Map<String,String> headers,int maxBytes,int timeoutSeconds){this.uri=uri;this.token=token;this.body=body;this.headers=headers;return response;}
    }
}
