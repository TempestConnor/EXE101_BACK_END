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
public class CancellationRequest {
    private Long cancellationId;
    private Long commissionId;
    private Long requestedByUserId;
    private String reason;
    private CancellationStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime resolvedAt;
}
