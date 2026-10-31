package com.stream2song.backend.service;

import java.net.URI;

public interface ClipMediaDownloader {
    DownloadedMedia download(URI uri);
    record DownloadedMedia(byte[] bytes, String contentType) { }
}
