package com.exe101.fulfillment.model;

import com.exe101.address.model.ShippingAddress;
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
    // What to make and where to send it, from the Order's immutable snapshots. Filled for the Admin's list and
    // transitions only; null on the internal paths that read a fulfillment for a refund.
    private Long orderId;
    private String artworkTitle;
    private String listingName;
    private String specifications;
    private Integer quantity;
    private ShippingAddress shippingAddress;
}
