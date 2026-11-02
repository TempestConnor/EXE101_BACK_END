package com.exe101.refund.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// shippingAmount and transfer are null until the request is approved; shippingAmount stays null unless this request
// refunded the Order's shipping. A Commission refund has commissionId and refundPercent instead of orderItemId.
public record RefundRequestDTO(
    Long refundRequestId, Long orderItemId, Long commissionId, String status, String reason,
    OffsetDateTime requestedAt, OffsetDateTime decidedAt, String decisionReason, Integer refundPercent,
    BigDecimal shippingAmount, RefundTransferDTO transfer
) {
}
