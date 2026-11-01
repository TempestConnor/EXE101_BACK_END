package com.exe101.order.dto;

import java.time.OffsetDateTime;

public record OrderItemFulfillmentDTO(
    String status, String carrier, String trackingReference, OffsetDateTime deliveredAt
) {
}
