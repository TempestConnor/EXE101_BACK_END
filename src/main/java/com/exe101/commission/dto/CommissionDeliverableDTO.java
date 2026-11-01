package com.exe101.commission.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record CommissionDeliverableDTO(
    Long deliverableId, Long commissionId, int versionNo, String notes, OffsetDateTime submittedAt,
    List<DeliverableFileDTO> files, List<RevisionRequestDTO> revisionRequests
) {
}
