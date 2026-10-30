package com.exe101.staff.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LoginStaffDTO(
    @NotBlank @Size(max = 160) String loginName,
    @NotNull @Size(min = 12, max = 128) String password
) {
}
