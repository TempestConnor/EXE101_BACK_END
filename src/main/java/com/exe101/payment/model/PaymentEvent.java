package com.exe101.payment.model;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One handled provider notification. Provider and external ID are unique, which deduplicates retries.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class PaymentEvent {
    private String providerName;
    private String externalEventId;
    private Long attemptId;
    private OffsetDateTime receivedAt;
    private OffsetDateTime processedAt;
    private EventOutcome outcome;
}
