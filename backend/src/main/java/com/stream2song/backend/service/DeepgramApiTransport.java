package com.stream2song.backend.service;

import java.net.http.HttpRequest;

public interface DeepgramApiTransport {
    Response send(HttpRequest request);
    record Response(int statusCode, byte[] body) { }
}
