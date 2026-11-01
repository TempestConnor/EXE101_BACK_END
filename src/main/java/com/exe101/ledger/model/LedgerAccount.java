package com.exe101.ledger.model;

// Debits are positive and credits negative, so CASH holds a positive balance and the payables a negative one.
public enum LedgerAccount {
    CASH,
    ARTIST_HELD,
    ARTIST_AVAILABLE,
    ARTIST_RELEASING,
    MANUFACTURER_PAYABLE,
    SHIPPING,
    TAX_PAYABLE,
    UNAPPLIED
}
