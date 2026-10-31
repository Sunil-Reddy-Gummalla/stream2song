package com.stream2song.backend.client;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

import com.stream2song.backend.service.OpenAiApiTransport;

@Component
public class JavaHttpOpenAiApiTransport implements OpenAiApiTransport {
    private static final int MAX_RESPONSE_BYTES = 1_000_000;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override public Response send(HttpRequest request) {
        try {
            CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, info -> new BoundedBodySubscriber());
            HttpResponse<byte[]> response = await(pending, request.timeout().orElse(Duration.ofSeconds(50)));
            return new Response(response.statusCode(), response.body());
        } catch (RuntimeException exception) {
            Throwable cause = exception.getCause();
            while (cause != null) {
                if (cause instanceof OpenAiTransportFailure failure) throw failure;
                if (cause instanceof java.net.http.HttpTimeoutException) throw new OpenAiTransportFailure(true, false);
                cause = cause.getCause();
            }
            throw exception;
        }
    }

    static <T> T await(CompletableFuture<T> pending, Duration deadline) {
        try {
            return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new OpenAiTransportFailure(true, false);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new OpenAiTransportFailure(true, false);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause != null) {
                if (cause instanceof OpenAiTransportFailure failure) throw failure;
                if (cause instanceof java.net.http.HttpTimeoutException) throw new OpenAiTransportFailure(true, false);
                cause = cause.getCause();
            }
            throw new OpenAiTransportFailure(false, false);
        }
    }

    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int length = buffer.remaining();
                if (output.size() + length > MAX_RESPONSE_BYTES) {
                    subscription.cancel();
                    body.completeExceptionally(new OpenAiTransportFailure(false, true));
                    return;
                }
                byte[] chunk = new byte[length];
                buffer.get(chunk);
                output.write(chunk, 0, chunk.length);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable throwable) { body.completeExceptionally(throwable); }
        @Override public void onComplete() { body.complete(output.toByteArray()); }
    }

    public static class OpenAiTransportFailure extends RuntimeException {
        private final boolean timeout;
        private final boolean oversized;
        public OpenAiTransportFailure(boolean timeout, boolean oversized) { this.timeout = timeout; this.oversized = oversized; }
        public boolean timeout() { return timeout; }
        public boolean oversized() { return oversized; }
    }
}
