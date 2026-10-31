package com.stream2song.backend.controller;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.stream2song.backend.exception.SongJobNotFoundException;
import com.stream2song.backend.service.TwitchOAuthFailure;
import com.stream2song.backend.service.TwitchClipDiscoveryService;
import com.stream2song.backend.service.TwitchClipMediaService;
import com.stream2song.backend.service.ClipTranscriptionService;
import com.stream2song.backend.service.TwitchClipMomentsService;
import com.stream2song.backend.service.MomentInterpretationService;
import com.stream2song.backend.service.MomentAnalysisSnapshotService;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        String errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, errors);
    }

    @ExceptionHandler(SongJobNotFoundException.class)
    public ProblemDetail handleNotFound(SongJobNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(TwitchOAuthFailure.class)
    public ProblemDetail handleTwitchConfiguration(TwitchOAuthFailure exception) {
        HttpStatus status = "configuration_missing".equals(exception.reason())
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST;
        return ProblemDetail.forStatusAndDetail(status, exception.reason());
    }

    @ExceptionHandler(TwitchClipDiscoveryService.ClipDiscoveryFailure.class)
    public ProblemDetail handleClipDiscovery(TwitchClipDiscoveryService.ClipDiscoveryFailure exception) {
        return ProblemDetail.forStatusAndDetail(exception.status(), exception.reason());
    }

    @ExceptionHandler(TwitchClipMediaService.ClipMediaFailure.class)
    public ProblemDetail handleClipMedia(TwitchClipMediaService.ClipMediaFailure exception) {
        return ProblemDetail.forStatusAndDetail(exception.status(), exception.reason());
    }

    @ExceptionHandler(ClipTranscriptionService.TranscriptionFailure.class)
    public ProblemDetail handleTranscription(ClipTranscriptionService.TranscriptionFailure exception) {
        return ProblemDetail.forStatusAndDetail(exception.status(), exception.reason());
    }

    @ExceptionHandler(TwitchClipMomentsService.MomentsFailure.class)
    public ProblemDetail handleMoments(TwitchClipMomentsService.MomentsFailure exception) {
        return ProblemDetail.forStatusAndDetail(exception.status(), exception.reason());
    }

    @ExceptionHandler(MomentInterpretationService.InterpretationFailure.class)
    public ProblemDetail handleInterpretation(MomentInterpretationService.InterpretationFailure exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(exception.status()), exception.reason());
    }

    @ExceptionHandler(MomentAnalysisSnapshotService.SnapshotFailure.class)
    public ProblemDetail handleSnapshot(MomentAnalysisSnapshotService.SnapshotFailure exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(exception.status()), exception.reason());
    }

    @ExceptionHandler(com.stream2song.backend.service.OpenAiMomentInterpretationProvider.InterpretationFailure.class)
    public ProblemDetail handleInterpretationProvider(
            com.stream2song.backend.service.OpenAiMomentInterpretationProvider.InterpretationFailure exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(exception.status()), exception.reason());
    }

    @ExceptionHandler(com.stream2song.backend.service.LyricsGenerationFailure.class)
    public ProblemDetail handleLyrics(com.stream2song.backend.service.LyricsGenerationFailure exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(exception.status()), exception.reason());
    }

    @ExceptionHandler(com.stream2song.backend.service.MusicGenerationFailure.class)
    public ProblemDetail handleMusic(com.stream2song.backend.service.MusicGenerationFailure exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(exception.status()),exception.reason());
    }

    @ExceptionHandler(com.stream2song.backend.service.SpokenLyricsFailure.class)
    public ProblemDetail handleSpokenLyrics(com.stream2song.backend.service.SpokenLyricsFailure exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(exception.status()), exception.reason());
    }
}
