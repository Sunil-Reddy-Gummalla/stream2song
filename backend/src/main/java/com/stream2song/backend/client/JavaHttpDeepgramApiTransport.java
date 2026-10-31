package com.stream2song.backend.client;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.stream2song.backend.service.DeepgramApiTransport;

@Component
public class JavaHttpDeepgramApiTransport implements DeepgramApiTransport {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override public Response send(HttpRequest request) {
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DeepgramTransportFailure(true);
        } catch (IOException exception) {
            throw new DeepgramTransportFailure(exception instanceof java.net.http.HttpTimeoutException);
        }
    }

    public static class DeepgramTransportFailure extends RuntimeException {
        private final boolean timeout;
        public DeepgramTransportFailure(boolean timeout) { this.timeout = timeout; }
        public boolean timeout() { return timeout; }
    }
}
