package com.exe101.payment.model;

// RECEIVED is allowed by the schema but unused: an event is recorded in the transaction that handles it.
public enum EventOutcome {
    RECEIVED,
    PROCESSED,
    IGNORED,
    EXCEPTION
}
