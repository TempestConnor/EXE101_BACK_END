package com.exe101.payment.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// amount is the installment being paid, not the whole Quote.
public record QuotePaymentAttemptDTO(
    Long attemptId, Long commissionId, Long quoteId, BigDecimal amount, String paymentUrl, OffsetDateTime expiresAt
) {
}
