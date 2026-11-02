package com.exe101.refund.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// The decided refund of a cancelled, paid Commission: the percentage and what it returns from the held first payment,
// the Artist's share and the tax half, each rounded half-up (approved 2 November 2026). The Commission feature works
// these out under its lock; the refund records them, against the installment's Payment.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class CommissionRefundSettlement {
    private Long refundRequestId;
    private Integer percent;
    private Long artistId;
    private Long quoteId;
    private Long paymentId;
    private BigDecimal paymentAmount;
    private BigDecimal share;
    private BigDecimal tax;
    private OffsetDateTime decidedAt;
    private Long decidedByStaffId;
    private String decisionReason;
}
