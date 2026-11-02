package com.exe101.commission.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// totalAmount = price + tax: what the Customer agrees to pay for this Quote, in two payments that add up to it: the
// first before the sketch, the final after the Customer approves the work. artistTermsId: the version of the Artist's
// terms the Customer accepted the Quote under; null before acceptance and for Quotes accepted before terms existed.
public record CommissionQuoteDTO(
    Long quoteId, Long commissionId, int sequenceNo, String scopeText, String expectedCompletionTerms,
    BigDecimal price, BigDecimal taxAmount, BigDecimal totalAmount, BigDecimal firstPaymentAmount,
    BigDecimal finalPaymentAmount, String currencyCode, String status,
    OffsetDateTime offeredAt, OffsetDateTime acceptedAt, OffsetDateTime paidAt, Long artistTermsId
) {
}
