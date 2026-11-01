package com.exe101.refund.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// The money returned for one approved request, from the Order's payment. The Admin sends it outside the system and
// records the outcome here (approved 1 November 2026).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class Transfer {
    private Long transferId;
    private Long refundRequestId;
    private Long paymentId;
    private BigDecimal amount;
    private String currencyCode;
    private TransferStatus status;
    private String transferReference;
    private OffsetDateTime createdAt;
    private OffsetDateTime succeededAt;
}
