package com.exe101.commission.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// totalAmount = price + tax: what the Customer agrees to pay for this Quote.
public record CommissionQuoteDTO(
    Long quoteId, Long commissionId, int sequenceNo, String scopeText, String expectedCompletionTerms,
    BigDecimal price, BigDecimal taxAmount, BigDecimal totalAmount, String currencyCode, String status,
    OffsetDateTime offeredAt, OffsetDateTime acceptedAt, OffsetDateTime paidAt
) {
}
