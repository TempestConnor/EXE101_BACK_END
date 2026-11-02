package com.exe101.commission.model;

import com.exe101.refund.model.RefundStatus;
import com.exe101.refund.model.Transfer;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// The refund of a paid Commission: the percentage of the held first payment the Artist offered with a cancellation,
// or the Admin set at a dispute (F20). REQUESTED while the cancellation is pending; an approved one has its transfer.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class CommissionRefund {
    private Long refundRequestId;
    private Long commissionId;
    private RefundStatus status;
    private Integer refundPercent;
    private String reason;
    private OffsetDateTime requestedAt;
    private OffsetDateTime decidedAt;
    private String decisionReason;
    private Transfer transfer;
}
