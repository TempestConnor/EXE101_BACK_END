package com.exe101.ledger.service;

import com.exe101.commission.model.QuoteSettlement;
import com.exe101.ledger.model.ArtistMovement;
import com.exe101.ledger.model.Reconciliation;
import com.exe101.ledger.model.RefundMovement;
import com.exe101.order.model.Order;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface LedgerService {
    void recordPayment(Order order);

    void recordQuotePayment(QuoteSettlement settlement);

    void recordUnapplied(Long providerEventId, BigDecimal amount, OffsetDateTime receivedAt);

    void recordProceedsReleased(ArtistMovement movement);

    void recordReleaseCreated(ArtistMovement movement);

    void recordReleasePaid(ArtistMovement movement);

    void recordReleaseFailed(ArtistMovement movement);

    void recordRefundApproved(RefundMovement movement);

    void recordRefundPaid(RefundMovement movement);

    Optional<Long> findRefundQuoteId(Long refundRequestId);

    Reconciliation reconcile();

    void requireReconciled();
}
