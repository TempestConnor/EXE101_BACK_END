package com.exe101.payment.dto;

import java.time.OffsetDateTime;

public record PaymentAttemptDTO(Long attemptId, Long orderId, String paymentUrl, OffsetDateTime expiresAt) {
}
