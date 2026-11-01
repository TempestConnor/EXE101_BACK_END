package com.exe101.commission.dto;

import java.time.OffsetDateTime;

public record CommissionMessageDTO(Long messageId, Long senderUserId, String body, OffsetDateTime sentAt) {
}
