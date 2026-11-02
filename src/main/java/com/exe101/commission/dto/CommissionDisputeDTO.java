package com.exe101.commission.dto;

import java.time.OffsetDateTime;

public record CommissionDisputeDTO(
    Long disputeId, Long commissionId, Long openedByUserId, String reason, String status, String outcome,
    Long decidedByStaffId, String resolutionReason, OffsetDateTime openedAt, OffsetDateTime resolvedAt
) {
}
