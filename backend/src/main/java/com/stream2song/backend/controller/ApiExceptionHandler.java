package com.stream2song.backend.controller;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.stream2song.backend.exception.SongJobNotFoundException;
import com.stream2song.backend.service.TwitchOAuthFailure;

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
}
