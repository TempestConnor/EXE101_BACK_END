package com.exe101.commission.dto;

import java.time.OffsetDateTime;

public record CommissionSummaryDTO(
    Long commissionId, Long customerId, Long artistId, String status, String customerNameSnapshot,
    String artistNameSnapshot, OffsetDateTime createdAt, OffsetDateTime completedAt
) {
}
