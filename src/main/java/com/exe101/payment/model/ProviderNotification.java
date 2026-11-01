package com.exe101.payment.model;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// A payment notification whose signature has been verified; the amount is in VND.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class ProviderNotification {
    private String eventId;
    private String attemptRef;
    private String transactionRef;
    private BigDecimal amount;
    private boolean success;
}
