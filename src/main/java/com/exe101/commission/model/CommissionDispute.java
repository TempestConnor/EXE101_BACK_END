package com.exe101.commission.model;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class CommissionDispute {
    private Long disputeId;
    private Long commissionId;
    private Long openedByUserId;
    private String reason;
    private DisputeStatus status;
    private DisputeOutcome outcome;
    private Long decidedByStaffId;
    private String resolutionReason;
    private OffsetDateTime openedAt;
    private OffsetDateTime resolvedAt;
}
