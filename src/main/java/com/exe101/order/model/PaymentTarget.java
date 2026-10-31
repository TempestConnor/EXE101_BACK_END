package com.exe101.order.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// The one Payment that settles an Order; attempts and provider events belong to F11.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class PaymentTarget {
    private Long paymentId;
    private Long orderId;
    private BigDecimal amount;
    private String currencyCode;
    private PaymentStatus status;
    private OffsetDateTime createdAt;
}
