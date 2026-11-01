package com.exe101.ledger.repository;

import com.exe101.entity.LedgerTransaction;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

// Read-only checks of the ledger against the payment, proceeds and release tables (Stripe-style completeness and
// clearing checks). Each query returns the IDs of the records that disagree.
public interface ReconciliationRepository extends Repository<LedgerTransaction, Long> {
    @Query("SELECT e.account AS account, SUM(e.amount) AS amount FROM LedgerEntry e GROUP BY e.account")
    List<AccountTotal> findAccountTotals();

    @Query("SELECT e.transactionId FROM LedgerEntry e GROUP BY e.transactionId HAVING SUM(e.amount) <> 0")
    List<Long> findUnbalancedTransactionIds();

    @Query("SELECT e.orderItemId AS orderItemId, e.account AS account, SUM(e.amount) AS amount FROM LedgerEntry e"
        + " WHERE e.orderItemId IS NOT NULL AND e.account IN :accounts GROUP BY e.orderItemId, e.account")
    List<ItemBalance> findItemBalances(Collection<String> accounts);

    @Query("SELECT p.proceedsId AS proceedsId, p.orderItemId AS orderItemId, p.amount AS amount, p.status AS status"
        + " FROM ArtistProceeds p WHERE p.orderItemId IS NOT NULL")
    List<ProceedsState> findOrderItemProceeds();

    @Query("SELECT p.paymentId FROM Payment p WHERE p.status = 'SUCCEEDED' AND p.orderId IS NOT NULL"
        + " AND NOT EXISTS (SELECT 1 FROM LedgerTransaction t WHERE t.type = 'PAYMENT_RECEIVED'"
        + " AND t.sourceType = 'PAYMENT' AND t.sourceId = p.paymentId)")
    List<Long> findPaymentsWithoutReceipt();

    @Query("SELECT t.sourceId FROM LedgerTransaction t WHERE t.type = 'PAYMENT_RECEIVED' AND NOT EXISTS"
        + " (SELECT 1 FROM Payment p WHERE p.paymentId = t.sourceId AND p.status = 'SUCCEEDED')")
    List<Long> findReceiptsWithoutPayment();

    // Every EXCEPTION outcome is a provider-confirmed success: real money that applies to no Order (payment.md B2).
    @Query("SELECT e.providerEventId FROM ProviderEvent e WHERE e.outcome = 'EXCEPTION'"
        + " AND NOT EXISTS (SELECT 1 FROM LedgerTransaction t WHERE t.type = 'PAYMENT_UNAPPLIED'"
        + " AND t.sourceType = 'PROVIDER_EVENT' AND t.sourceId = e.providerEventId)")
    List<Long> findExceptionsWithoutEntry();

    @Query("SELECT p.proceedsId FROM ArtistProceeds p WHERE p.orderItemId IS NOT NULL AND p.status <> 'HELD'"
        + " AND p.amount <> 0 AND NOT EXISTS (SELECT 1 FROM LedgerTransaction t WHERE t.type = 'PROCEEDS_RELEASED'"
        + " AND t.sourceType = 'PROCEEDS' AND t.sourceId = p.proceedsId)")
    List<Long> findReleasedProceedsWithoutEntry();

    @Query("SELECT p.payoutId FROM Payout p WHERE NOT EXISTS (SELECT 1 FROM LedgerTransaction t"
        + " WHERE t.type = 'RELEASE_CREATED' AND t.sourceType = 'PAYOUT' AND t.sourceId = p.payoutId)")
    List<Long> findReleasesWithoutEntry();

    @Query("SELECT p.payoutId FROM Payout p WHERE p.status = :status AND NOT EXISTS (SELECT 1 FROM LedgerTransaction t"
        + " WHERE t.type = :type AND t.sourceType = 'PAYOUT' AND t.sourceId = p.payoutId)")
    List<Long> findReleasesWithoutOutcome(String status, String type);

    @Query("SELECT t.sourceId FROM LedgerTransaction t WHERE t.type = :type AND NOT EXISTS (SELECT 1 FROM Payout p"
        + " WHERE p.payoutId = t.sourceId AND p.status = :status)")
    List<Long> findOutcomesWithoutRelease(String type, String status);

    @Query("SELECT t.sourceId FROM LedgerTransaction t, LedgerEntry e, Payment p WHERE e.transactionId = t.transactionId"
        + " AND t.type = 'PAYMENT_RECEIVED' AND e.account = 'CASH' AND p.paymentId = t.sourceId"
        + " GROUP BY t.sourceId, p.amount HAVING SUM(e.amount) <> p.amount")
    List<Long> findReceiptsWithWrongAmount();
}
