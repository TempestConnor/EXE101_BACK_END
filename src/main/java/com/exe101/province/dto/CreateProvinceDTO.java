package com.exe101.province.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProvinceDTO(
    // Official two-digit code of a province-level unit.
    @NotNull @Pattern(regexp = "\\d{2}") String provinceCode,
    @NotBlank @Size(max = 160) String provinceName
) {
}
