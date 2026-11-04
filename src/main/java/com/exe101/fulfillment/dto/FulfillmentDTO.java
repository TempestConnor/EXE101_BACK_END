package com.exe101.fulfillment.dto;

import com.exe101.order.dto.OrderShippingAddressDTO;
import java.time.OffsetDateTime;

// The item and the address come from the Order's snapshots, so they show what was bought and where it was to be
// sent at checkout. No prices, costs or proceeds: the Admin needs none of them to make and ship the item.
public record FulfillmentDTO(
    Long orderItemId, Long orderId, String status, String carrier, String trackingReference,
    OffsetDateTime deliveredAt, OffsetDateTime updatedAt, String artworkTitle, String listingName,
    String specifications, Integer quantity, OrderShippingAddressDTO shippingAddress
) {
}
