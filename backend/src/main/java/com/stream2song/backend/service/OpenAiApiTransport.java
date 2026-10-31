package com.stream2song.backend.service;

import java.net.http.HttpRequest;

public interface OpenAiApiTransport {
    Response send(HttpRequest request);
    record Response(int statusCode, byte[] body) { }
}
