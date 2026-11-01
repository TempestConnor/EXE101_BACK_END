package com.exe101.fulfillment.model;

// CANCELLED is set only by an approved refund (F15); FAILED is unused (approved 1 November 2026).
public enum FulfillmentStatus {
    PENDING,
    IN_PRODUCTION,
    READY_TO_SHIP,
    SHIPPED,
    DELIVERED,
    FAILED,
    CANCELLED
}
