package com.exe101.refund.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One whole-item refund request (§16.3–5), or the refund of a cancelled, paid Commission (F20), which carries the
// percentage of the held first payment it returns. An approved one has its transfer, and the Order's shipping when it
// was the last physical item to be refunded.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class Refund {
    private Long refundRequestId;
    private Long orderItemId;
    private Long commissionId;
    private Long requestedByUserId;
    private String reason;
    private RefundStatus status;
    private OffsetDateTime requestedAt;
    private OffsetDateTime decidedAt;
    private Long decidedByStaffId;
    private String decisionReason;
    private Integer refundPercent;
    private BigDecimal shippingAmount;
    private Transfer transfer;
}
