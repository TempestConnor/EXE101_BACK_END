package com.exe101.ledger.service;

import com.exe101.ledger.model.ArtistMovement;
import com.exe101.ledger.model.Reconciliation;
import com.exe101.order.model.Order;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public interface LedgerService {
    void recordPayment(Order order);

    void recordUnapplied(Long providerEventId, BigDecimal amount, OffsetDateTime receivedAt);

    void recordProceedsReleased(ArtistMovement movement);

    void recordReleaseCreated(ArtistMovement movement);

    void recordReleasePaid(ArtistMovement movement);

    void recordReleaseFailed(ArtistMovement movement);

    Reconciliation reconcile();

    void requireReconciled();
}
