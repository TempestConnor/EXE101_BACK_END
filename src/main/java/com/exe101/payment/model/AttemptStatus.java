package com.exe101.payment.model;

// PENDING is allowed by the schema but unused: an attempt stays INITIATED until the provider reports its result.
public enum AttemptStatus {
    INITIATED,
    PENDING,
    SUCCEEDED,
    FAILED,
    EXCEPTION
}
