package com.exe101.proceeds.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ReleaseDTO(
    Long payoutId, Long artistId, Long proceedsId, BigDecimal amount, String currencyCode, String status,
    String transferReference, OffsetDateTime createdAt, OffsetDateTime paidAt
) {
}
