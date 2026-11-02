package com.exe101.commission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResolveDisputeDTO(
    @NotNull @Pattern(regexp = "CONTINUE|CANCEL_REFUND|CANCEL_NO_REFUND") String outcome,
    @NotBlank @Size(max = 2000) String reason
) {
}
