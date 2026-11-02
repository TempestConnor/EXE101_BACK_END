package com.exe101.commission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The Artist's request carries their refund offer; the Customer's never does.
public record RequestCancellationDTO(@NotBlank @Size(max = 5000) String reason, Integer refundPercent) {
}
