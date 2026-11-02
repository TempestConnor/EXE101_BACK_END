package com.exe101.commission.dto;

import java.time.OffsetDateTime;

public record CancellationRequestDTO(
    Long cancellationId, Long commissionId, Long requestedByUserId, String reason, String status,
    OffsetDateTime createdAt, OffsetDateTime resolvedAt
) {
}
