package com.exe101.ledger.service;

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
import com.exe101.ledger.repository.ItemBalance;
import com.exe101.ledger.repository.LedgerEntryRepository;
import com.exe101.ledger.repository.LedgerTransactionRepository;
import com.exe101.ledger.repository.ProceedsState;
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
    // Where an item's proceeds must sit for each status; PAID, and VOID once refunds exist, leave nothing owed.
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
                line(LedgerAccount.ARTIST_RELEASING, movement.getArtistId(), movement.getOrderItemId(), amount),
                line(LedgerAccount.CASH, null, null, amount.negate())));
    }

    @Override
    public void recordReleaseFailed(ArtistMovement movement) {
        move(LedgerEventType.RELEASE_FAILED, LedgerSourceType.PAYOUT, movement, LedgerAccount.ARTIST_RELEASING,
            LedgerAccount.ARTIST_AVAILABLE);
    }

    @Override
    @Transactional(readOnly = true)
    public Reconciliation reconcile() {
        List<Mismatch> mismatches = Stream.of(
                mismatches(MismatchCheck.UNBALANCED_TRANSACTION, "LedgerTransaction",
                    reconciliationRepository.findUnbalancedTransactionIds()),
                disagreements(),
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
                        "FAILED")))
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
            line(from, movement.getArtistId(), movement.getOrderItemId(), amount),
            line(to, movement.getArtistId(), movement.getOrderItemId(), amount.negate())));
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
}
