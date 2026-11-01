package com.exe101.refund.model;

// REQUESTED -> APPROVED | REJECTED, then APPROVED -> REFUNDED when the transfer succeeds. PROCESSING and FAILED are
// in the schema but unused: a failed transfer is retried and the request stays APPROVED (approved 1 November 2026).
public enum RefundStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    PROCESSING,
    REFUNDED,
    FAILED
}
