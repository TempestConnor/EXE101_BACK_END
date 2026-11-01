package com.exe101.fulfillment.model;

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
public class FulfillmentChange {
    private FulfillmentStatus status;
    private String carrier;
    private String trackingReference;
    private String reason;
}
