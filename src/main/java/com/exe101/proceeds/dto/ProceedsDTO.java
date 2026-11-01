package com.exe101.proceeds.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// One of orderItemId or quoteId; installment (1 or 2) only for a Quote.
public record ProceedsDTO(
    Long proceedsId, Long orderItemId, Long quoteId, Integer installment, BigDecimal amount, String currencyCode, String status,
    OffsetDateTime eligibleAt, OffsetDateTime paidAt
) {
}
