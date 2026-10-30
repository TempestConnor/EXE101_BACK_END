package com.exe101.tag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateTagDTO(
    @NotBlank @Size(max = 100) String name,
    @NotNull Boolean active
) {
}
