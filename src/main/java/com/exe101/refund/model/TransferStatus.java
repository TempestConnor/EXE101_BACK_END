package com.exe101.refund.model;

// PENDING -> SUCCEEDED | FAILED, and FAILED -> PENDING to retry on the same row; SUCCEEDED is final.
public enum TransferStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
