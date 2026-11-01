package com.exe101.commission.model;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// The pending Payment of one Quote installment, ready for a new provider attempt.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class QuotePayment {
    private Long paymentId;
    private Long commissionId;
    private Long quoteId;
    private int installment;
    private BigDecimal amount;
}
