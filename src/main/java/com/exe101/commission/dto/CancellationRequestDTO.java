package com.exe101.commission.dto;

import java.time.OffsetDateTime;

// refundPercent is the Artist's refund offer while the request is pending, null when none has been made yet.
public record CancellationRequestDTO(
    Long cancellationId, Long commissionId, Long requestedByUserId, String reason, String status,
    OffsetDateTime createdAt, OffsetDateTime resolvedAt, Integer refundPercent
) {
}
