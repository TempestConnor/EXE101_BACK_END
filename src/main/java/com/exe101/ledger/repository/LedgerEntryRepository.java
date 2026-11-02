package com.exe101.ledger.repository;

import com.exe101.entity.LedgerEntry;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    // The Quote a Commission refund's approval made the Customer owed for.
    @Query("SELECT e.quoteId FROM LedgerEntry e, LedgerTransaction t WHERE e.transactionId = t.transactionId"
        + " AND t.type = 'REFUND_APPROVED' AND t.sourceType = 'REFUND_REQUEST' AND t.sourceId = :refundRequestId"
        + " AND e.account = 'REFUND_PAYABLE' AND e.quoteId IS NOT NULL")
    Optional<Long> findRefundQuoteId(Long refundRequestId);
}
