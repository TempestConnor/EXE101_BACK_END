package com.exe101.artwork.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record MovePreviewDTO(
    @NotNull @PositiveOrZero Integer sortOrder
) {
}
