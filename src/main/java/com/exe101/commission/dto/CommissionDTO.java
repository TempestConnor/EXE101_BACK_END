package com.exe101.commission.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record CommissionDTO(
    Long commissionId, Long customerId, Long artistId, String requestDescription, String status,
    String customerNameSnapshot, String artistNameSnapshot, boolean allowPublicDisplay, OffsetDateTime createdAt,
    OffsetDateTime completedAt, List<CommissionAttachmentDTO> attachments
) {
}
