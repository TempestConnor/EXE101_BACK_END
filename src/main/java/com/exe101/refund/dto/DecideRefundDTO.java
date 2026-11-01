package com.exe101.refund.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// A rejection needs a reason; the service checks that, so a missing one is reported by name.
public record DecideRefundDTO(
    @NotNull @Pattern(regexp = "APPROVE|REJECT") String decision,
    @Size(max = 2000) String reason
) {
}
