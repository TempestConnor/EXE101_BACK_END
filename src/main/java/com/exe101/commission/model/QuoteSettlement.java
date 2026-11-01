package com.exe101.commission.model;

import com.exe101.order.model.PaymentSettlement;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// How a provider-confirmed payment was applied to its Quote installment. When it is PAID, the amount splits into the
// Artist's share of the price (proceeds, fee 0) and the tax; the two add up to the amount. completed is set when the
// final installment completed the Commission, which releases the Quote's proceeds.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class QuoteSettlement {
    private PaymentSettlement settlement;
    private Long paymentId;
    private Long commissionId;
    private Long quoteId;
    private Long artistId;
    private int installment;
    private BigDecimal amount;
    private BigDecimal proceeds;
    private BigDecimal taxAmount;
    private OffsetDateTime paidAt;
    private boolean completed;
}
