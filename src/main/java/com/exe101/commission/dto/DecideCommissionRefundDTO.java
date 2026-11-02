package com.exe101.commission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DecideCommissionRefundDTO(@NotNull Integer percent, @NotBlank @Size(max = 2000) String reason) {
}
