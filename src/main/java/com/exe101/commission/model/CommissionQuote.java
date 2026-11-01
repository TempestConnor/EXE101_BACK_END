package com.exe101.commission.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class CommissionQuote {
    private Long quoteId;
    private Long commissionId;
    private int sequenceNo;
    private String scopeText;
    private String expectedCompletionTerms;
    private BigDecimal price;
    private BigDecimal taxAmount;
    private BigDecimal firstPaymentAmount;
    private BigDecimal finalPaymentAmount;
    private String currencyCode;
    private QuoteStatus status;
    private OffsetDateTime offeredAt;
    private OffsetDateTime acceptedAt;
    private OffsetDateTime paidAt;
}
