package com.stream2song.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.stream2song.backend.config.OpenAiProperties;

class OpenAiLyricsGenerationProviderTest {
    private final JsonMapper mapper=JsonMapper.builder().build();
    @Test void requestsStructuredOutputAndParsesLyricsWithoutExternalCall() throws Exception {
        String structured="{\"title\":\"Bright Loop\",\"sections\":[{\"kind\":\"verse\",\"label\":\"Verse 1\",\"lines\":[\"Line one\",\"Line two\"],\"sourceMomentIds\":[\"m1\"],\"sourceEvidenceIds\":[\"m1-t0\"]}]}";
        String response="{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":"+mapper.writeValueAsString(structured)+"}]}]}";
        var transport=new Capture(new OpenAiApiTransport.Response(200,response.getBytes(StandardCharsets.UTF_8)));
        var provider=new OpenAiLyricsGenerationProvider(new OpenAiProperties("test-secret","gpt-6-luna"),mapper,transport);
        var draft=provider.generate("{\"moments\":[],\"transcriptContexts\":[{\"id\":\"clip-transcript-context\",\"kind\":\"clip_context\",\"evidence\":[{\"id\":\"clip-transcript-1\",\"excerpt\":\"bullshit application\",\"startSeconds\":1.0,\"endSeconds\":1.5},{\"id\":\"clip-transcript-2\",\"excerpt\":\"how can I\",\"startSeconds\":2.0,\"endSeconds\":2.5}]}]}","pop","playful","medium");
        assertEquals("Bright Loop",draft.title()); assertEquals("m1-t0",draft.sections().getFirst().sourceEvidenceIds().getFirst());
        assertEquals("https://api.openai.com/v1/responses",transport.request.uri().toString());
        assertEquals("Bearer test-secret",transport.request.headers().firstValue("Authorization").orElseThrow());
        var body=mapper.readTree(readBody(transport.request));
        assertEquals("gpt-6-luna",body.path("model").asText()); assertFalse(body.path("store").asBoolean());
        assertEquals(0,body.path("tools").size()); assertTrue(body.path("text").path("format").path("strict").asBoolean());
        assertTrue(body.path("input").get(0).path("content").asText().contains("untrusted data"));
        assertTrue(body.path("input").get(0).path("content").asText().contains("living artist"));
        assertTrue(body.path("input").get(0).path("content").asText().contains("concrete subject"));
        assertTrue(body.path("input").get(1).path("content").asText().contains("sourceEvidence"));
        assertTrue(body.path("input").get(1).path("content").asText().contains("bullshit application"));
        assertTrue(body.path("input").get(1).path("content").asText().contains("how can I"));
    }
    @Test void rejectsRefusalAndNoncompletedStatus() {
        assertBad("{\"status\":\"incomplete\",\"output\":[]}");
        assertBad("{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"refusal\"}]}]}");
    }
    @Test void sendsSessionChatAsUntimedBackgroundContext() throws Exception {
        String structured="{\"title\":\"Chat Flavor\",\"sections\":[{\"kind\":\"verse\",\"label\":\"Verse 1\",\"lines\":[\"Line one\",\"Line two\"],\"sourceMomentIds\":[\"session-chat-context\"],\"sourceEvidenceIds\":[\"session-chat-1\"]},{\"kind\":\"chorus\",\"label\":\"Chorus\",\"lines\":[\"Line three\",\"Line four\"],\"sourceMomentIds\":[\"session-chat-context\"],\"sourceEvidenceIds\":[\"session-chat-1\"]},{\"kind\":\"verse\",\"label\":\"Verse 2\",\"lines\":[\"Line five\",\"Line six\"],\"sourceMomentIds\":[\"session-chat-context\"],\"sourceEvidenceIds\":[\"session-chat-1\"]}]}";
        String response="{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":"+mapper.writeValueAsString(structured)+"}]}]}";
        var transport=new Capture(new OpenAiApiTransport.Response(200,response.getBytes(StandardCharsets.UTF_8)));
        var provider=new OpenAiLyricsGenerationProvider(new OpenAiProperties("test-secret","gpt-6-luna"),mapper,transport);
        var draft=provider.generate("{\"moments\":[],\"sessionContexts\":[{\"id\":\"session-chat-context\",\"kind\":\"session_context\",\"association\":\"unknown\",\"startSeconds\":null,\"endSeconds\":null,\"evidence\":[{\"id\":\"session-chat-1\",\"kind\":\"session_chat_context\",\"excerpt\":\"that was so funny\",\"startSeconds\":null,\"endSeconds\":null}]}]}","pop","playful","medium");
        assertEquals("session-chat-context",draft.sections().getFirst().sourceMomentIds().getFirst());
        var body=mapper.readTree(readBody(transport.request));
        String system=body.path("input").get(0).path("content").asText().toLowerCase();
        String user=body.path("input").get(1).path("content").asText();
        assertTrue(system.contains("background"));
        assertTrue(system.contains("session"));
        assertTrue(user.contains("session_context"));
        assertTrue(user.contains("session-chat-1"));
        assertTrue(user.contains("\"startSeconds\":null"));
    }
    private void assertBad(String body) {
        var provider=new OpenAiLyricsGenerationProvider(new OpenAiProperties("x","gpt-6-luna"),mapper,
                r->new OpenAiApiTransport.Response(200,body.getBytes(StandardCharsets.UTF_8)));
        assertThrows(LyricsGenerationFailure.class,()->provider.generate("{}","pop","playful","medium"));
    }
    private String readBody(HttpRequest request) throws Exception {
        var future=new CompletableFuture<String>(); var out=new StringBuilder();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);} public void onNext(ByteBuffer b){byte[] x=new byte[b.remaining()];b.get(x);out.append(new String(x,StandardCharsets.UTF_8));}
            public void onError(Throwable t){future.completeExceptionally(t);} public void onComplete(){future.complete(out.toString());}
        }); return future.get();
    }
    private static final class Capture implements OpenAiApiTransport {
        private final OpenAiApiTransport.Response response; private HttpRequest request;
        private Capture(OpenAiApiTransport.Response response){this.response=response;}
        public OpenAiApiTransport.Response send(HttpRequest r){request=r;return response;}
    }
}
