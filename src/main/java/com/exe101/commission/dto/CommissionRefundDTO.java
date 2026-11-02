package com.exe101.commission.dto;

import com.exe101.refund.dto.RefundTransferDTO;
import java.time.OffsetDateTime;

// REQUESTED is an offer waiting for its cancellation to be answered; transfer is null unless the refund was APPROVED
// with an amount above 0.
public record CommissionRefundDTO(
    Long refundRequestId, Long commissionId, String status, Integer refundPercent, String reason,
    OffsetDateTime requestedAt, OffsetDateTime decidedAt, String decisionReason, RefundTransferDTO transfer
) {
}
