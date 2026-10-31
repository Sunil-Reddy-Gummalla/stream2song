package com.stream2song.backend.client;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import com.stream2song.backend.service.ReplicateHttpTransport;

@Component
public class JavaHttpReplicateTransport implements ReplicateHttpTransport {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    @Override public Response exchange(String method,java.net.URI uri,String bearerToken,byte[] body,Map<String,String> headers,int maxBytes,int timeoutSeconds) {
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            HttpRequest.Builder builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds));
            if(bearerToken!=null) builder.header("Authorization","Bearer "+bearerToken);
            if(body!=null) builder.header("Content-Type","application/json");
            headers.forEach(builder::header);
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
            pending=client.sendAsync(builder.build(),info->new BoundedSubscriber(maxBytes));
            HttpResponse<byte[]> response=await(pending,timeoutSeconds);
            return new Response(response.statusCode(),response.body(),response.headers().firstValue("Content-Type").orElse(""));
        } catch(TransportFailure e){throw e;}catch(RuntimeException e){throw new TransportFailure(false);}
    }
    static <T> T await(CompletableFuture<T> pending,int timeoutSeconds) {
        try{return pending.get(timeoutSeconds,TimeUnit.SECONDS);}
        catch(InterruptedException e){pending.cancel(true);Thread.currentThread().interrupt();throw new TransportFailure(true);}
        catch(TimeoutException e){pending.cancel(true);throw new TransportFailure(true);}
        catch(ExecutionException e){Throwable c=e.getCause();while(c!=null){if(c instanceof OversizedBody)throw new TransportFailure(false,true);if(c instanceof java.net.http.HttpTimeoutException)throw new TransportFailure(true);c=c.getCause();}throw new TransportFailure(false);}
    }
    private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int max; private final CompletableFuture<byte[]> body=new CompletableFuture<>(); private final ByteArrayOutputStream out=new ByteArrayOutputStream(); private Flow.Subscription subscription;
        BoundedSubscriber(int max){this.max=max;} public java.util.concurrent.CompletionStage<byte[]> getBody(){return body;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers){for(ByteBuffer b:buffers){int n=b.remaining();if(out.size()+n>max){subscription.cancel();body.completeExceptionally(new OversizedBody());return;}byte[] chunk=new byte[n];b.get(chunk);out.write(chunk,0,n);}subscription.request(1);}
        public void onError(Throwable t){body.completeExceptionally(t);} public void onComplete(){body.complete(out.toByteArray());}
    }
    private static final class OversizedBody extends RuntimeException { }
    public static class TransportFailure extends RuntimeException { private final boolean timeout,oversized; public TransportFailure(boolean timeout){this(timeout,false);} public TransportFailure(boolean timeout,boolean oversized){this.timeout=timeout;this.oversized=oversized;} public boolean timeout(){return timeout;} public boolean oversized(){return oversized;} }
}
