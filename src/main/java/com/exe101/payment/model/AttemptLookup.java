package com.exe101.payment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// What the buyer's return page needs after VNPay: what was paid and the attempt status only the signed IPN sets.
// Either the Order or the Quote fields are set.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class AttemptLookup {
    private String attemptRef;
    private AttemptStatus status;
    private Long orderId;
    private Long commissionId;
    private Long quoteId;
    private Integer installment;
}
