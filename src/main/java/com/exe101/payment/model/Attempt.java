package com.exe101.payment.model;

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
public class Attempt {
    private Long attemptId;
    private Long paymentId;
    private String providerName;
    private String providerAttemptRef;
    private String providerTransactionRef;
    private AttemptStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime confirmedAt;
}
