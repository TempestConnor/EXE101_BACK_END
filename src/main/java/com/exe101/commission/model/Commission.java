package com.exe101.commission.model;

import java.time.OffsetDateTime;
import java.util.List;
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
public class Commission {
    private Long commissionId;
    private Long customerId;
    private Long artistId;
    private String requestDescription;
    private CommissionStatus status;
    private String customerNameSnapshot;
    private String artistNameSnapshot;
    private boolean allowPublicDisplay;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;
    private List<CommissionAttachment> attachments;
}
