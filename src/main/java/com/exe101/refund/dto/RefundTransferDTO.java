package com.exe101.refund.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record RefundTransferDTO(
    Long transferId, BigDecimal amount, String currencyCode, String status, String transferReference,
    OffsetDateTime createdAt, OffsetDateTime succeededAt
) {
}
