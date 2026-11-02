package com.exe101.commission.model;

// CANCEL_REFUND and CANCEL_NO_REFUND both cancel the Commission and leave its held funds HELD; the refund decision
// itself belongs to Commission refunds (F20, approved 2 November 2026).
public enum DisputeOutcome {
    CONTINUE,
    CANCEL_REFUND,
    CANCEL_NO_REFUND
}
