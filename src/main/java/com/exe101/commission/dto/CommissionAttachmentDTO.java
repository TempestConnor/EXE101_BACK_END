package com.exe101.commission.dto;

import java.time.OffsetDateTime;

public record CommissionAttachmentDTO(
    Long attachmentId, Long uploadedByUserId, String originalName, String contentType, long byteLength,
    OffsetDateTime createdAt
) {
}
