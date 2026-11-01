package com.exe101.refund.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Every listed status is accepted here, so the service can say which change is not allowed.
public record RecordTransferOutcomeDTO(
    @NotNull @Pattern(regexp = "PENDING|SUCCEEDED|FAILED") String status,
    @Size(max = 200) String transferReference,
    @Size(max = 2000) String reason
) {
}
