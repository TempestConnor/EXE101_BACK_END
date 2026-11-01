package com.exe101.order.model;

// How a provider-confirmed payment was applied to its Order (F11).
public enum PaymentSettlement {
    // The Order is now PAID and its Payment SUCCEEDED.
    PAID,
    // The confirmed amount differs from the Payment; nothing changed.
    WRONG_AMOUNT,
    // Another attempt already paid the Order; nothing changed.
    ALREADY_PAID,
    // The Order was past its payment window or no longer pending; its Payment is now EXCEPTION.
    NOT_PAYABLE
}
