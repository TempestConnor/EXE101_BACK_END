package com.exe101.commission.dto;

import com.exe101.refund.dto.RefundTransferDTO;
import java.time.OffsetDateTime;

// stage says who acts next while the refund is REQUESTED, and is null once it is decided; transfer is null unless
// it was APPROVED with an amount above 0.
public record CommissionRefundDTO(
    Long refundRequestId, Long commissionId, String status, String stage, Integer refundPercent, String reason,
    OffsetDateTime requestedAt, OffsetDateTime decidedAt, String decisionReason, RefundTransferDTO transfer
) {
}
