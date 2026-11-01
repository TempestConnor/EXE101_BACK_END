package com.exe101.ledger.repository;

import com.exe101.entity.LedgerTransaction;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

// Read-only checks of the ledger against the payment, proceeds, release and refund tables (Stripe-style completeness and
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

    // VOID proceeds were refunded while still held, so they were never released.
    @Query("SELECT p.proceedsId FROM ArtistProceeds p WHERE p.orderItemId IS NOT NULL"
        + " AND p.status NOT IN ('HELD', 'VOID') AND p.amount <> 0 AND NOT EXISTS (SELECT 1 FROM LedgerTransaction t WHERE t.type = 'PROCEEDS_RELEASED'"
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

    // An approved item refund stays approved through its transfer, until it is REFUNDED.
    @Query("SELECT r.refundRequestId FROM RefundRequest r WHERE r.orderItemId IS NOT NULL"
        + " AND r.status IN ('APPROVED', 'PROCESSING', 'REFUNDED') AND NOT EXISTS (SELECT 1 FROM LedgerTransaction t"
        + " WHERE t.type = 'REFUND_APPROVED' AND t.sourceType = 'REFUND_REQUEST' AND t.sourceId = r.refundRequestId)")
    List<Long> findApprovedRefundsWithoutEntry();

    @Query("SELECT t.sourceId FROM LedgerTransaction t WHERE t.type = 'REFUND_APPROVED' AND NOT EXISTS"
        + " (SELECT 1 FROM RefundRequest r WHERE r.refundRequestId = t.sourceId"
        + " AND r.status IN ('APPROVED', 'PROCESSING', 'REFUNDED'))")
    List<Long> findRefundEntriesWithoutApproval();

    // What the approval made owed to the buyer must be exactly what the transfer sends.
    @Query("SELECT x.refundTransferId FROM RefundTransfer x, LedgerTransaction t, LedgerEntry e"
        + " WHERE t.type = 'REFUND_APPROVED' AND t.sourceType = 'REFUND_REQUEST' AND t.sourceId = x.refundRequestId"
        + " AND e.transactionId = t.transactionId AND e.account = 'REFUND_PAYABLE'"
        + " GROUP BY x.refundTransferId, x.amount HAVING SUM(e.amount) + x.amount <> 0")
    List<Long> findTransfersDisagreeingWithApproval();

    @Query("SELECT x.refundTransferId FROM RefundTransfer x WHERE x.status = 'SUCCEEDED' AND NOT EXISTS"
        + " (SELECT 1 FROM LedgerTransaction t WHERE t.type = 'REFUND_PAID' AND t.sourceType = 'REFUND_TRANSFER'"
        + " AND t.sourceId = x.refundTransferId)")
    List<Long> findSucceededTransfersWithoutEntry();

    @Query("SELECT t.sourceId FROM LedgerTransaction t WHERE t.type = 'REFUND_PAID' AND NOT EXISTS"
        + " (SELECT 1 FROM RefundTransfer x WHERE x.refundTransferId = t.sourceId AND x.status = 'SUCCEEDED')")
    List<Long> findRefundPaymentsWithoutTransfer();

    @Query("SELECT t.sourceId FROM LedgerTransaction t, LedgerEntry e, RefundTransfer x"
        + " WHERE e.transactionId = t.transactionId AND t.type = 'REFUND_PAID' AND e.account = 'CASH'"
        + " AND x.refundTransferId = t.sourceId GROUP BY t.sourceId, x.amount HAVING SUM(e.amount) + x.amount <> 0")
    List<Long> findRefundPaymentsWithWrongAmount();
}
