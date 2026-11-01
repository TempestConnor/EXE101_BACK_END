package com.exe101.commission.dto;

import java.time.OffsetDateTime;

public record RevisionRequestDTO(
    Long revisionId, Long deliverableId, Long requestedByUserId, String details, OffsetDateTime requestedAt
) {
}
