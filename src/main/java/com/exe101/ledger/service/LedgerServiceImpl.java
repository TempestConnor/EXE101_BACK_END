package com.exe101.ledger.service;

import com.exe101.commission.model.QuoteSettlement;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.LedgerTransaction;
import com.exe101.ledger.mapper.LedgerEntityMapper;
import com.exe101.ledger.model.AccountBalance;
import com.exe101.ledger.model.ArtistMovement;
import com.exe101.ledger.model.LedgerAccount;
import com.exe101.ledger.model.LedgerEventType;
import com.exe101.ledger.model.LedgerLine;
import com.exe101.ledger.model.LedgerPosting;
import com.exe101.ledger.model.LedgerSourceType;
import com.exe101.ledger.model.Mismatch;
import com.exe101.ledger.model.MismatchCheck;
import com.exe101.ledger.model.Reconciliation;
import com.exe101.ledger.model.RefundMovement;
import com.exe101.ledger.repository.ItemBalance;
import com.exe101.ledger.repository.LedgerEntryRepository;
import com.exe101.ledger.repository.LedgerTransactionRepository;
import com.exe101.ledger.repository.ProceedsState;
import com.exe101.ledger.repository.QuoteBalance;
import com.exe101.ledger.repository.QuoteProceedsState;
import com.exe101.ledger.repository.ReconciliationRepository;
import com.exe101.listing.model.ListingType;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderLine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// MANDATORY: a ledger transaction commits with the money event it records, or not at all. Lines are checked to add
// up to 0 before anything is written; UX_LedgerTransaction_Source records each event once, and the immutability
// triggers keep every written line unchanged.
@Slf4j
@Service
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class LedgerServiceImpl implements LedgerService {
    private static List<LedgerAccount> artistAccounts = List.of(LedgerAccount.ARTIST_HELD,
        LedgerAccount.ARTIST_AVAILABLE, LedgerAccount.ARTIST_RELEASING);
    // Where an item's proceeds must sit for each status; PAID and VOID (refunded) leave nothing owed.
    private static Map<String, LedgerAccount> proceedsAccounts = Map.of(
        "HELD", LedgerAccount.ARTIST_HELD,
        "ELIGIBLE", LedgerAccount.ARTIST_AVAILABLE,
        "RESERVED", LedgerAccount.ARTIST_RELEASING);
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final ReconciliationRepository reconciliationRepository;
    private final LedgerEntityMapper mapper;

    // The whole Order total arrives in CASH and is owed onward from the snapshots: each item's proceeds to its
    // Artist, its manufacturing cost and tax, and the Order's shipping. The snapshots record a 0 fee; a fee would
    // leave the lines unbalanced and be rejected, because no account receives it.
    @Override
    public void recordPayment(Order order) {
        List<LedgerLine> lines = new ArrayList<>();
        lines.add(line(LedgerAccount.CASH, null, null, order.getTotalAmount()));

        for (OrderLine item : order.getItems()) {
            lines.add(line(LedgerAccount.ARTIST_HELD, item.getArtistId(), item.getOrderItemId(),
                item.getArtistProceeds().negate()));
            lines.add(line(LedgerAccount.TAX_PAYABLE, null, item.getOrderItemId(), item.getTaxAmount().negate()));

            if (item.getListingType() == ListingType.PHYSICAL) {
                BigDecimal manufacturing = item.getManufacturingCostPerUnit()
                    .multiply(BigDecimal.valueOf(item.getQuantity()));
                lines.add(line(LedgerAccount.MANUFACTURER_PAYABLE, null, item.getOrderItemId(), manufacturing.negate()));
            }
        }

        lines.add(line(LedgerAccount.SHIPPING, null, null, order.getShippingAmount().negate()));
        post(LedgerEventType.PAYMENT_RECEIVED, LedgerSourceType.PAYMENT, order.getPayment().getPaymentId(),
            order.getPaidAt(), lines);
    }

    // A Quote installment arrives in CASH and is owed onward: its share of the price to the Artist, held until the
    // Commission's release milestone, and its share of the tax. The Quote snapshot records a 0 fee.
    @Override
    public void recordQuotePayment(QuoteSettlement settlement) {
        Long quoteId = settlement.getQuoteId();
        post(LedgerEventType.PAYMENT_RECEIVED, LedgerSourceType.PAYMENT, settlement.getPaymentId(),
            settlement.getPaidAt(), List.of(
                line(LedgerAccount.CASH, null, null, settlement.getAmount()),
                quoteLine(LedgerAccount.ARTIST_HELD, settlement.getArtistId(), quoteId,
                    settlement.getProceeds().negate()),
                quoteLine(LedgerAccount.TAX_PAYABLE, null, quoteId, settlement.getTaxAmount().negate())));
    }

    // §12.12: money the provider confirmed that applies to no Order is kept apart until it is refunded by hand.
    @Override
    public void recordUnapplied(Long providerEventId, BigDecimal amount, OffsetDateTime receivedAt) {
        if (amount.signum() <= 0) {
            log.warn("[LEDGER] - ACTION: recordUnapplied: providerEventId: {}: nothing received", providerEventId);
            return;
        }

        post(LedgerEventType.PAYMENT_UNAPPLIED, LedgerSourceType.PROVIDER_EVENT, providerEventId, receivedAt, List.of(
            line(LedgerAccount.CASH, null, null, amount),
            line(LedgerAccount.UNAPPLIED, null, null, amount.negate())));
    }

    @Override
    public void recordProceedsReleased(ArtistMovement movement) {
        move(LedgerEventType.PROCEEDS_RELEASED, LedgerSourceType.PROCEEDS, movement, LedgerAccount.ARTIST_HELD,
            LedgerAccount.ARTIST_AVAILABLE);
    }

    @Override
    public void recordReleaseCreated(ArtistMovement movement) {
        move(LedgerEventType.RELEASE_CREATED, LedgerSourceType.PAYOUT, movement, LedgerAccount.ARTIST_AVAILABLE,
            LedgerAccount.ARTIST_RELEASING);
    }

    // The money leaves: what was owed to the Artist and the cash that covered it both go down.
    @Override
    public void recordReleasePaid(ArtistMovement movement) {
        BigDecimal amount = movement.getAmount();
        post(LedgerEventType.RELEASE_PAID, LedgerSourceType.PAYOUT, movement.getSourceId(), movement.getOccurredAt(),
            List.of(
                artistLine(LedgerAccount.ARTIST_RELEASING, movement, amount),
                line(LedgerAccount.CASH, null, null, amount.negate())));
    }

    @Override
    public void recordReleaseFailed(ArtistMovement movement) {
        move(LedgerEventType.RELEASE_FAILED, LedgerSourceType.PAYOUT, movement, LedgerAccount.ARTIST_RELEASING,
            LedgerAccount.ARTIST_AVAILABLE);
    }

    // An approved refund takes back what the item or the Quote still owed, and the shipping when it is the last
    // physical item; the buyer is owed the sum until the transfer succeeds. A failed transfer writes nothing (approved
    // 1 November 2026), so the money stays in REFUND_PAYABLE until a retry succeeds. A Commission refund names its
    // Quote, and has no manufacturing cost or shipping.
    @Override
    public void recordRefundApproved(RefundMovement movement) {
        post(LedgerEventType.REFUND_APPROVED, LedgerSourceType.REFUND_REQUEST, movement.getSourceId(),
            movement.getOccurredAt(), List.of(
                refundLine(LedgerAccount.ARTIST_HELD, movement.getArtistId(), movement, movement.getProceeds()),
                refundLine(LedgerAccount.MANUFACTURER_PAYABLE, null, movement, movement.getManufacturing()),
                refundLine(LedgerAccount.TAX_PAYABLE, null, movement, movement.getTax()),
                line(LedgerAccount.SHIPPING, null, null, movement.getShipping()),
                refundLine(LedgerAccount.REFUND_PAYABLE, null, movement, movement.getAmount().negate())));
    }

    @Override
    public void recordRefundPaid(RefundMovement movement) {
        BigDecimal amount = movement.getAmount();
        post(LedgerEventType.REFUND_PAID, LedgerSourceType.REFUND_TRANSFER, movement.getSourceId(),
            movement.getOccurredAt(), List.of(
                refundLine(LedgerAccount.REFUND_PAYABLE, null, movement, amount),
                line(LedgerAccount.CASH, null, null, amount.negate())));
    }

    @Override
    @Transactional(readOnly = true)
    public Reconciliation reconcile() {
        List<Mismatch> mismatches = Stream.of(
                mismatches(MismatchCheck.UNBALANCED_TRANSACTION, "LedgerTransaction",
                    reconciliationRepository.findUnbalancedTransactionIds()),
                disagreements(),
                quoteDisagreements(),
                mismatches(MismatchCheck.MISSING_ENTRY, "Payment",
                    reconciliationRepository.findPaymentsWithoutReceipt()),
                mismatches(MismatchCheck.UNEXPECTED_ENTRY, "Payment",
                    reconciliationRepository.findReceiptsWithoutPayment()),
                mismatches(MismatchCheck.AMOUNT_DISAGREES, "Payment",
                    reconciliationRepository.findReceiptsWithWrongAmount()),
                mismatches(MismatchCheck.MISSING_ENTRY, "ProviderEvent",
                    reconciliationRepository.findExceptionsWithoutEntry()),
                mismatches(MismatchCheck.MISSING_ENTRY, "ArtistProceeds",
                    reconciliationRepository.findReleasedProceedsWithoutEntry()),
                mismatches(MismatchCheck.MISSING_ENTRY, "Payout", reconciliationRepository.findReleasesWithoutEntry()),
                mismatches(MismatchCheck.MISSING_ENTRY, "Payout",
                    reconciliationRepository.findReleasesWithoutOutcome("PAID", LedgerEventType.RELEASE_PAID.name())),
                mismatches(MismatchCheck.MISSING_ENTRY, "Payout",
                    reconciliationRepository.findReleasesWithoutOutcome("FAILED",
                        LedgerEventType.RELEASE_FAILED.name())),
                mismatches(MismatchCheck.UNEXPECTED_ENTRY, "Payout",
                    reconciliationRepository.findOutcomesWithoutRelease(LedgerEventType.RELEASE_PAID.name(), "PAID")),
                mismatches(MismatchCheck.UNEXPECTED_ENTRY, "Payout",
                    reconciliationRepository.findOutcomesWithoutRelease(LedgerEventType.RELEASE_FAILED.name(),
                        "FAILED")),
                mismatches(MismatchCheck.MISSING_ENTRY, "RefundRequest",
                    reconciliationRepository.findApprovedRefundsWithoutEntry()),
                mismatches(MismatchCheck.UNEXPECTED_ENTRY, "RefundRequest",
                    reconciliationRepository.findRefundEntriesWithoutApproval()),
                mismatches(MismatchCheck.AMOUNT_DISAGREES, "RefundTransfer",
                    reconciliationRepository.findTransfersDisagreeingWithApproval()),
                mismatches(MismatchCheck.MISSING_ENTRY, "RefundTransfer",
                    reconciliationRepository.findSucceededTransfersWithoutEntry()),
                mismatches(MismatchCheck.UNEXPECTED_ENTRY, "RefundTransfer",
                    reconciliationRepository.findRefundPaymentsWithoutTransfer()),
                mismatches(MismatchCheck.AMOUNT_DISAGREES, "RefundTransfer",
                    reconciliationRepository.findRefundPaymentsWithWrongAmount()))
            .flatMap(List::stream)
            .toList();
        List<AccountBalance> balances = reconciliationRepository.findAccountTotals().stream()
            .map(total -> AccountBalance.builder()
                .withAccount(LedgerAccount.valueOf(total.getAccount()))
                .withAmount(total.getAmount())
                .build())
            .sorted(Comparator.comparing(AccountBalance::getAccount))
            .toList();

        return Reconciliation.builder()
            .withClean(mismatches.isEmpty())
            .withBalances(balances)
            .withMismatches(mismatches)
            .build();
    }

    // Money cannot leave while the books disagree (approved 1 November 2026).
    @Override
    @Transactional(readOnly = true)
    public void requireReconciled() {
        Reconciliation reconciliation = reconcile();

        if (!reconciliation.isClean()) {
            log.warn("[LEDGER] - ACTION: requireReconciled: mismatches: {}", reconciliation.getMismatches().size());
            throw new AppException(AppErrorMessage.RECONCILIATION_MISMATCH);
        }
    }

    private void move(
        LedgerEventType type, LedgerSourceType sourceType, ArtistMovement movement, LedgerAccount from,
        LedgerAccount to
    ) {
        BigDecimal amount = movement.getAmount();
        post(type, sourceType, movement.getSourceId(), movement.getOccurredAt(), List.of(
            artistLine(from, movement, amount),
            artistLine(to, movement, amount.negate())));
    }

    // Lines of 0 carry nothing and are left out; an event that moves nothing is not recorded.
    private void post(
        LedgerEventType type, LedgerSourceType sourceType, Long sourceId, OffsetDateTime occurredAt,
        List<LedgerLine> lines
    ) {
        List<LedgerLine> moving = lines.stream().filter(line -> line.getAmount().signum() != 0).toList();
        BigDecimal total = moving.stream().map(LedgerLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        if (total.signum() != 0) {
            throw new IllegalStateException("Unbalanced ledger posting: " + type + " " + sourceType + " " + sourceId
                + ": " + total.toPlainString());
        }

        if (moving.isEmpty()) {
            return;
        }

        LedgerPosting posting = LedgerPosting.builder()
            .withType(type)
            .withSourceType(sourceType)
            .withSourceId(sourceId)
            .withOccurredAt(occurredAt)
            .withLines(moving)
            .build();
        LedgerTransaction saved = transactionRepository.saveAndFlush(mapper.toEntity(posting));
        entryRepository.saveAllAndFlush(moving.stream()
            .map(line -> mapper.toEntity(line, saved.getTransactionId()))
            .toList());
        log.info("[LEDGER] - ACTION: post: type: {}: sourceType: {}: sourceId: {}: transactionId: {}", type,
            sourceType, sourceId, saved.getTransactionId());
    }

    // Each item's Artist balances must match its proceeds status exactly, and no item may owe without proceeds.
    private List<Mismatch> disagreements() {
        Map<Long, Map<String, BigDecimal>> balances = reconciliationRepository
            .findItemBalances(artistAccounts.stream().map(Enum::name).toList()).stream()
            .collect(Collectors.groupingBy(ItemBalance::getOrderItemId,
                Collectors.toMap(ItemBalance::getAccount, ItemBalance::getAmount)));
        List<ProceedsState> proceeds = reconciliationRepository.findOrderItemProceeds();
        Set<Long> itemsWithProceeds = proceeds.stream().map(ProceedsState::getOrderItemId)
            .collect(Collectors.toSet());
        List<Long> disagreeing = proceeds.stream()
            .filter(state -> !agrees(state, balances.getOrDefault(state.getOrderItemId(), Map.of())))
            .map(ProceedsState::getProceedsId)
            .toList();
        List<Long> orphans = balances.entrySet().stream()
            .filter(entry -> !itemsWithProceeds.contains(entry.getKey()))
            .filter(entry -> entry.getValue().values().stream().anyMatch(amount -> amount.signum() != 0))
            .map(Map.Entry::getKey)
            .toList();
        List<Mismatch> found = new ArrayList<>(mismatches(MismatchCheck.STATUS_DISAGREES, "ArtistProceeds",
            disagreeing));
        found.addAll(mismatches(MismatchCheck.UNEXPECTED_ENTRY, "OrderItem", orphans));

        return found;
    }

    // A Quote's installments share its Artist accounts, so its balances must equal what its proceeds owe in each.
    private List<Mismatch> quoteDisagreements() {
        Map<Long, Map<String, BigDecimal>> balances = reconciliationRepository
            .findQuoteBalances(artistAccounts.stream().map(Enum::name).toList()).stream()
            .collect(Collectors.groupingBy(QuoteBalance::getQuoteId,
                Collectors.toMap(QuoteBalance::getAccount, QuoteBalance::getAmount)));
        Map<Long, List<QuoteProceedsState>> proceeds = reconciliationRepository.findQuoteProceeds().stream()
            .collect(Collectors.groupingBy(QuoteProceedsState::getQuoteId));
        List<Long> disagreeing = proceeds.entrySet().stream()
            .filter(entry -> !quoteAgrees(entry.getValue(), balances.getOrDefault(entry.getKey(), Map.of())))
            .flatMap(entry -> entry.getValue().stream().map(QuoteProceedsState::getProceedsId))
            .toList();
        List<Long> orphans = balances.entrySet().stream()
            .filter(entry -> !proceeds.containsKey(entry.getKey()))
            .filter(entry -> entry.getValue().values().stream().anyMatch(amount -> amount.signum() != 0))
            .map(Map.Entry::getKey)
            .toList();
        List<Mismatch> found = new ArrayList<>(mismatches(MismatchCheck.STATUS_DISAGREES, "ArtistProceeds",
            disagreeing));
        found.addAll(mismatches(MismatchCheck.UNEXPECTED_ENTRY, "CommissionQuote", orphans));

        return found;
    }

    private boolean quoteAgrees(List<QuoteProceedsState> states, Map<String, BigDecimal> actual) {
        Map<LedgerAccount, BigDecimal> expected = new HashMap<>();

        for (QuoteProceedsState state : states) {
            LedgerAccount owedIn = proceedsAccounts.get(state.getStatus());

            if (owedIn != null) {
                expected.merge(owedIn, state.getAmount().negate(), BigDecimal::add);
            }
        }

        return artistAccounts.stream().allMatch(account ->
            actual.getOrDefault(account.name(), BigDecimal.ZERO)
                .compareTo(expected.getOrDefault(account, BigDecimal.ZERO)) == 0);
    }

    private boolean agrees(ProceedsState state, Map<String, BigDecimal> actual) {
        Map<LedgerAccount, BigDecimal> expected = new HashMap<>();
        LedgerAccount owedIn = proceedsAccounts.get(state.getStatus());

        if (owedIn != null) {
            expected.put(owedIn, state.getAmount().negate());
        }

        return artistAccounts.stream().allMatch(account ->
            actual.getOrDefault(account.name(), BigDecimal.ZERO)
                .compareTo(expected.getOrDefault(account, BigDecimal.ZERO)) == 0);
    }

    private List<Mismatch> mismatches(MismatchCheck check, String recordType, List<Long> recordIds) {
        return recordIds.stream()
            .map(recordId -> Mismatch.builder()
                .withCheck(check)
                .withRecordType(recordType)
                .withRecordId(recordId)
                .build())
            .toList();
    }

    private LedgerLine line(LedgerAccount account, Long artistId, Long orderItemId, BigDecimal amount) {
        return LedgerLine.builder()
            .withAccount(account)
            .withArtistId(artistId)
            .withOrderItemId(orderItemId)
            .withAmount(amount)
            .build();
    }

    // An Artist account line names the Order Item or the Quote the money is owed for.
    private LedgerLine artistLine(LedgerAccount account, ArtistMovement movement, BigDecimal amount) {
        return LedgerLine.builder()
            .withAccount(account)
            .withArtistId(movement.getArtistId())
            .withOrderItemId(movement.getOrderItemId())
            .withQuoteId(movement.getQuoteId())
            .withAmount(amount)
            .build();
    }

    // A refund line names the Order Item or the Quote the refund is for.
    private LedgerLine refundLine(LedgerAccount account, Long artistId, RefundMovement movement, BigDecimal amount) {
        return LedgerLine.builder()
            .withAccount(account)
            .withArtistId(artistId)
            .withOrderItemId(movement.getOrderItemId())
            .withQuoteId(movement.getQuoteId())
            .withAmount(amount)
            .build();
    }

    private LedgerLine quoteLine(LedgerAccount account, Long artistId, Long quoteId, BigDecimal amount) {
        return LedgerLine.builder()
            .withAccount(account)
            .withArtistId(artistId)
            .withQuoteId(quoteId)
            .withAmount(amount)
            .build();
    }
}
