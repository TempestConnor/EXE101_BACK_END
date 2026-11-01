package com.exe101.commission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateCommissionDTO(
    @NotNull @Positive Long artistId,
    @NotBlank @Size(max = 5000) String description,
    @NotNull Boolean allowPublicDisplay
) {
}
