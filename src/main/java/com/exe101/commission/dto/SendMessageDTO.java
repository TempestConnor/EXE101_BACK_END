package com.exe101.commission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageDTO(@NotBlank @Size(max = 5000) String body) {
}
