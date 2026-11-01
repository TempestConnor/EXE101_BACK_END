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
public class CommissionDeliverable {
    private Long deliverableId;
    private Long commissionId;
    private int versionNo;
    private String notes;
    private OffsetDateTime submittedAt;
    private List<DeliverableFile> files;
    private List<RevisionRequest> revisionRequests;
}
