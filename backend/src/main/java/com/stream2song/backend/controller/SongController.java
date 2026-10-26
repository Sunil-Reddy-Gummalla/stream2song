package com.stream2song.backend.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stream2song.backend.dto.CreateSongRequest;
import com.stream2song.backend.dto.SongJobResponse;
import com.stream2song.backend.service.SongJobService;

@RestController
@RequestMapping("/api/songs")
public class SongController {
    private final SongJobService songJobService;

    public SongController(SongJobService songJobService) {
        this.songJobService = songJobService;
    }

    @PostMapping
    public ResponseEntity<SongJobResponse> create(@Valid @RequestBody CreateSongRequest request) {
        SongJobResponse response = SongJobResponse.from(songJobService.create(request.channelName()));
        return ResponseEntity.created(URI.create("/api/songs/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public SongJobResponse get(@PathVariable UUID id) {
        return SongJobResponse.from(songJobService.get(id));
    }

    @GetMapping
    public List<SongJobResponse> list() {
        return songJobService.list().stream().map(SongJobResponse::from).toList();
    }
}
