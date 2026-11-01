package com.exe101.refund.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// shippingAmount and transfer are null until the request is approved; shippingAmount stays null unless this request
// refunded the Order's shipping.
public record RefundRequestDTO(
    Long refundRequestId, Long orderItemId, String status, String reason, OffsetDateTime requestedAt,
    OffsetDateTime decidedAt, String decisionReason, BigDecimal shippingAmount, RefundTransferDTO transfer
) {
}
