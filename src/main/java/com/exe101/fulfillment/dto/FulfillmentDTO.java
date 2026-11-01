package com.exe101.fulfillment.dto;

import java.time.OffsetDateTime;

public record FulfillmentDTO(
    Long orderItemId, String status, String carrier, String trackingReference, OffsetDateTime deliveredAt,
    OffsetDateTime updatedAt
) {
}
