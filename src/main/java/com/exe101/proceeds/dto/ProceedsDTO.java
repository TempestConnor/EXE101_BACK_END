package com.exe101.proceeds.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ProceedsDTO(
    Long proceedsId, Long orderItemId, BigDecimal amount, String currencyCode, String status,
    OffsetDateTime eligibleAt, OffsetDateTime paidAt
) {
}
