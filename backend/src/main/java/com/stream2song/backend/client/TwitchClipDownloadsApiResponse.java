package com.stream2song.backend.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TwitchClipDownloadsApiResponse(List<ClipDownload> data) {
    public record ClipDownload(
            @JsonProperty("clip_id") String clipId,
            @JsonProperty("landscape_download_url") String landscapeDownloadUrl,
            @JsonProperty("portrait_download_url") String portraitDownloadUrl) { }
}
