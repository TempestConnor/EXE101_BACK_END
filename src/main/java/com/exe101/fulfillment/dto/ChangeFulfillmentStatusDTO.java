package com.exe101.fulfillment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Every listed status is accepted here, so the service can say which change is not allowed.
public record ChangeFulfillmentStatusDTO(
    @NotNull @Pattern(regexp = "PENDING|IN_PRODUCTION|READY_TO_SHIP|SHIPPED|DELIVERED|FAILED|CANCELLED") String status,
    @Size(max = 120) String carrier,
    @Size(max = 160) String trackingReference,
    @Size(max = 1000) String reason
) {
}
