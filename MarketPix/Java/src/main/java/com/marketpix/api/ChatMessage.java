package com.marketpix.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatMessage(
        @NotBlank
        @Pattern(regexp = "user|model")
        String role,
        @NotBlank
        @Size(max = 10000)
        String content) {
}
