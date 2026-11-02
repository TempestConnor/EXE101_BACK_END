package com.exe101.commission.model;

// Where an open Commission refund waits: for the Artist's percentage, for the Customer's answer to it, or for the
// Admin after the Customer objected (approved 2 November 2026). A decided refund has no stage.
public enum CommissionRefundStage {
    AWAITING_PERCENTAGE,
    AWAITING_CUSTOMER,
    AWAITING_ADMIN
}
