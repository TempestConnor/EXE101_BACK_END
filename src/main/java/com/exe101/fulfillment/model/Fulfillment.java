package com.exe101.fulfillment.model;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One physical Order Item; every copy of its quantity shares this state (§6.5).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class Fulfillment {
    private Long orderItemId;
    private FulfillmentStatus status;
    private String carrier;
    private String trackingReference;
    private OffsetDateTime deliveredAt;
    private OffsetDateTime updatedAt;
}
