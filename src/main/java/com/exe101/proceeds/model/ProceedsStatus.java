package com.exe101.proceeds.model;

// VOID is set by an approved refund (F15), only while the proceeds are HELD.
public enum ProceedsStatus {
    HELD,
    ELIGIBLE,
    RESERVED,
    PAID,
    VOID
}
