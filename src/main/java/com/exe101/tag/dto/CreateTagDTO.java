package com.exe101.tag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTagDTO(
    @NotBlank @Size(max = 100) String name
) {
}
