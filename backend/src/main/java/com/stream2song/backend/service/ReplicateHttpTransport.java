package com.stream2song.backend.service;

import java.net.URI;
import java.util.Map;

public interface ReplicateHttpTransport {
    Response exchange(String method, URI uri, String bearerToken, byte[] requestBody, Map<String,String> headers, int maxBytes, int timeoutSeconds);
    record Response(int status,byte[] body,String contentType) { }
}
