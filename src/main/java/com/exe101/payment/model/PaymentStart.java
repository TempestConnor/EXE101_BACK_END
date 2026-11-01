package com.exe101.payment.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// A new attempt and the provider page where the buyer pays; that page stops accepting payment at expiresAt. It pays
// an Order, or one installment of a Commission Quote (1 = first, 2 = final; null for an Order).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class PaymentStart {
    private Long attemptId;
    private Long orderId;
    private Long commissionId;
    private Long quoteId;
    private Integer installment;
    private BigDecimal amount;
    private String paymentUrl;
    private OffsetDateTime expiresAt;
}
