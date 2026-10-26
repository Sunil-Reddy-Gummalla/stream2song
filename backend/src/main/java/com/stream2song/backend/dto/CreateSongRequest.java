package com.stream2song.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateSongRequest(
        @NotBlank(message = "channelName is required")
        @Pattern(
                regexp = "^[A-Za-z0-9_]{4,25}$",
                message = "channelName must be 4 to 25 letters, numbers, or underscores"
        )
        String channelName
) {
}
