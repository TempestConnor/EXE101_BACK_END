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
public class RevisionRequest {
    private Long revisionId;
    private Long deliverableId;
    private Long commissionId;
    private Long requestedByUserId;
    private String details;
    private OffsetDateTime requestedAt;
}
