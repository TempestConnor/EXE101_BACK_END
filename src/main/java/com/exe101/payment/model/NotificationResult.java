package com.exe101.payment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// The outcome recorded for a notification (the earlier one for a duplicate, none for a rejected signature) and
// the reply sent to the provider.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class NotificationResult {
    private EventOutcome outcome;
    private boolean duplicate;
    private VnpayReply reply;
}
