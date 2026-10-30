package com.exe101.province.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateProvinceSupportDTO(
    @NotNull Boolean supported,
    @Size(max = 2000) String reason
) {
}
