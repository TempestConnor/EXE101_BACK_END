package com.exe101.refund.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRefundRequestDTO(@NotBlank @Size(max = 2000) String reason) {
}
