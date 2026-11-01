package com.exe101.order.model;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Where a physical item is in its fulfillment, as the buyer sees it; the fulfillment feature changes it.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class ItemFulfillment {
    private String status;
    private String carrier;
    private String trackingReference;
    private OffsetDateTime deliveredAt;
}
