package com.exe101.payment.model;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// A new attempt and the provider page where the buyer pays; that page stops accepting payment at expiresAt.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class PaymentStart {
    private Long attemptId;
    private Long orderId;
    private String paymentUrl;
    private OffsetDateTime expiresAt;
}
