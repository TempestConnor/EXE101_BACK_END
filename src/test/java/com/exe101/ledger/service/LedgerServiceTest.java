package com.exe101.ledger.service;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.LedgerEntry;
import com.exe101.entity.LedgerTransaction;
import com.exe101.ledger.mapper.LedgerEntityMapperImpl;
import com.exe101.ledger.model.ArtistMovement;
import com.exe101.ledger.model.LedgerAccount;
import com.exe101.ledger.model.Mismatch;
import com.exe101.ledger.model.MismatchCheck;
import com.exe101.ledger.repository.AccountTotal;
import com.exe101.ledger.repository.ItemBalance;
import com.exe101.ledger.repository.LedgerEntryRepository;
import com.exe101.ledger.repository.LedgerTransactionRepository;
import com.exe101.ledger.repository.ProceedsState;
import com.exe101.ledger.repository.ReconciliationRepository;
import com.exe101.listing.model.ListingType;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderLine;
import com.exe101.order.model.OrderStatus;
import com.exe101.order.model.PaymentTarget;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Lenient: each test uses part of the shared persistence.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LedgerServiceTest {
    private static long paymentId = 400L;
    private static long digitalItemId = 310L;
    private static long physicalItemId = 311L;
    private static long digitalArtistId = 21L;
    private static long physicalArtistId = 22L;
    @Mock
    private LedgerTransactionRepository transactionRepository;
    @Mock
    private LedgerEntryRepository entryRepository;
    @Mock
    private ReconciliationRepository reconciliationRepository;
    private LedgerServiceImpl service;
    private List<LedgerTransaction> savedTransactions;
    private List<LedgerEntry> savedEntries;

    @BeforeEach
    void setUp() {
        service = new LedgerServiceImpl(transactionRepository, entryRepository, reconciliationRepository,
            new LedgerEntityMapperImpl());
        savedTransactions = new ArrayList<>();
        savedEntries = new ArrayList<>();
        when(transactionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            LedgerTransaction saved = invocation.getArgument(0);
            saved.setTransactionId(900L + savedTransactions.size());
            savedTransactions.add(saved);
            return saved;
        });
        when(entryRepository.saveAllAndFlush(anyList())).thenAnswer(invocation -> {
            List<LedgerEntry> saved = invocation.getArgument(0);
            savedEntries.addAll(saved);
            return saved;
        });
    }

    @Test
    void payment_received_entries_balance_ok() {
        // given
        // A digital item (50 000) and two prints (2 x (80 000 cost + 30 000 markup)), 5 000 tax, 30 000 shipping.
        var order = paidOrder(BigDecimal.ZERO);

        // when
        service.recordPayment(order);

        // then
        var transaction = savedTransactions.getFirst();
        assertAll(
            () -> assertThat(savedTransactions).hasSize(1),
            () -> assertThat(transaction.getType()).isEqualTo("PAYMENT_RECEIVED"),
            () -> assertThat(transaction.getSourceType()).isEqualTo("PAYMENT"),
            () -> assertThat(transaction.getSourceId()).isEqualTo(paymentId),
            () -> assertThat(total(savedEntries)).isEqualByComparingTo("0"),
            () -> assertThat(sum(LedgerAccount.CASH, null)).isEqualByComparingTo("305000"),
            () -> assertThat(sum(LedgerAccount.ARTIST_HELD, digitalItemId)).isEqualByComparingTo("-50000"),
            () -> assertThat(sum(LedgerAccount.ARTIST_HELD, physicalItemId)).isEqualByComparingTo("-60000"),
            () -> assertThat(sum(LedgerAccount.MANUFACTURER_PAYABLE, physicalItemId))
                .isEqualByComparingTo("-160000"),
            () -> assertThat(sum(LedgerAccount.TAX_PAYABLE, physicalItemId)).isEqualByComparingTo("-5000"),
            () -> assertThat(sum(LedgerAccount.SHIPPING, null)).isEqualByComparingTo("-30000"),
            () -> assertThat(savedEntries).filteredOn(entry -> entry.getAccount().equals("ARTIST_HELD"))
                .extracting(LedgerEntry::getArtistId).containsExactly(digitalArtistId, physicalArtistId),
            () -> assertThat(savedEntries).extracting(LedgerEntry::getCurrencyCode).containsOnly("VND"),
            () -> assertThat(savedEntries).extracting(LedgerEntry::getAmount)
                .allSatisfy(amount -> assertThat(amount.signum()).isNotZero())
        );
    }

    @Test
    void unbalanced_transaction_rejected_ko() {
        // given
        // A fee taken from the digital item has no account to go to, so the lines cannot add up to 0.
        var order = paidOrder(new BigDecimal("5000"));

        // when / then
        assertThatThrownBy(() -> service.recordPayment(order)).isInstanceOf(IllegalStateException.class);
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(entryRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void release_moves_held_to_releasing_ok() {
        // given
        var proceeds = movement(500L, "60000");
        var release = movement(600L, "60000");

        // when
        service.recordProceedsReleased(proceeds);
        service.recordReleaseCreated(release);

        // then
        assertAll(
            () -> assertThat(savedTransactions).extracting(LedgerTransaction::getType)
                .containsExactly("PROCEEDS_RELEASED", "RELEASE_CREATED"),
            () -> assertThat(savedTransactions).extracting(LedgerTransaction::getSourceType)
                .containsExactly("PROCEEDS", "PAYOUT"),
            () -> assertThat(savedTransactions).extracting(LedgerTransaction::getSourceId)
                .containsExactly(500L, 600L),
            () -> assertThat(sum(LedgerAccount.ARTIST_HELD, physicalItemId)).isEqualByComparingTo("60000"),
            () -> assertThat(sum(LedgerAccount.ARTIST_AVAILABLE, physicalItemId)).isEqualByComparingTo("0"),
            () -> assertThat(sum(LedgerAccount.ARTIST_RELEASING, physicalItemId)).isEqualByComparingTo("-60000"),
            () -> assertThat(savedEntries).extracting(LedgerEntry::getArtistId).containsOnly(physicalArtistId)
        );
    }

    @Test
    void zero_proceeds_writes_no_entries_ok() {
        // given
        var proceeds = movement(500L, "0");

        // when
        service.recordProceedsReleased(proceeds);

        // then
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(entryRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void release_paid_clears_releasing_ok() {
        // given
        var release = movement(600L, "60000");
        service.recordReleaseCreated(release);

        // when
        service.recordReleasePaid(release);

        // then
        assertAll(
            () -> assertThat(savedTransactions.getLast().getType()).isEqualTo("RELEASE_PAID"),
            () -> assertThat(sum(LedgerAccount.ARTIST_RELEASING, physicalItemId)).isEqualByComparingTo("0"),
            () -> assertThat(sum(LedgerAccount.CASH, null)).isEqualByComparingTo("-60000"),
            () -> assertThat(total(savedEntries)).isEqualByComparingTo("0")
        );
    }

    @Test
    void release_failed_then_rerelease_ok() {
        // given
        var failed = movement(600L, "60000");
        var renewed = movement(601L, "60000");
        service.recordProceedsReleased(movement(500L, "60000"));
        service.recordReleaseCreated(failed);

        // when
        service.recordReleaseFailed(failed);
        var afterFailure = sum(LedgerAccount.ARTIST_AVAILABLE, physicalItemId);
        var releasingAfterFailure = sum(LedgerAccount.ARTIST_RELEASING, physicalItemId);
        service.recordReleaseCreated(renewed);

        // then
        assertAll(
            () -> assertThat(afterFailure).isEqualByComparingTo("-60000"),
            () -> assertThat(releasingAfterFailure).isEqualByComparingTo("0"),
            () -> assertThat(savedTransactions).extracting(LedgerTransaction::getType)
                .containsExactly("PROCEEDS_RELEASED", "RELEASE_CREATED", "RELEASE_FAILED", "RELEASE_CREATED"),
            () -> assertThat(sum(LedgerAccount.ARTIST_AVAILABLE, physicalItemId)).isEqualByComparingTo("0"),
            () -> assertThat(sum(LedgerAccount.ARTIST_RELEASING, physicalItemId)).isEqualByComparingTo("-60000")
        );
    }

    @Test
    void reconciliation_clean_ok() {
        // given
        when(reconciliationRepository.findOrderItemProceeds())
            .thenReturn(List.of(proceeds(500L, physicalItemId, "60000", "HELD")));
        when(reconciliationRepository.findItemBalances(anyCollection()))
            .thenReturn(List.of(itemBalance(physicalItemId, "ARTIST_HELD", "-60000")));
        when(reconciliationRepository.findAccountTotals()).thenReturn(List.of(accountTotal("CASH", "305000"),
            accountTotal("ARTIST_HELD", "-60000")));

        // when
        var reconciliation = service.reconcile();

        // then
        assertAll(
            () -> assertThat(reconciliation.isClean()).isTrue(),
            () -> assertThat(reconciliation.getMismatches()).isEmpty(),
            () -> assertThat(reconciliation.getBalances()).extracting(balance -> balance.getAccount())
                .containsExactly(LedgerAccount.CASH, LedgerAccount.ARTIST_HELD),
            () -> assertThatCode(() -> service.requireReconciled()).doesNotThrowAnyException()
        );
    }

    @Test
    void reconciliation_detects_status_mismatch_ko() {
        // given
        // The proceeds say a release is under way, but the ledger still holds the money.
        when(reconciliationRepository.findOrderItemProceeds())
            .thenReturn(List.of(proceeds(500L, physicalItemId, "60000", "RESERVED")));
        when(reconciliationRepository.findItemBalances(anyCollection()))
            .thenReturn(List.of(itemBalance(physicalItemId, "ARTIST_HELD", "-60000")));

        // when
        var reconciliation = service.reconcile();

        // then
        assertAll(
            () -> assertThat(reconciliation.isClean()).isFalse(),
            () -> assertThat(reconciliation.getMismatches()).singleElement()
                .satisfies(mismatch -> assertMismatch(mismatch, MismatchCheck.STATUS_DISAGREES, "ArtistProceeds",
                    500L)),
            () -> assertThatThrownBy(() -> service.requireReconciled())
                .isInstanceOfSatisfying(AppException.class, exception ->
                    assertThat(exception.getError()).isEqualTo(AppErrorMessage.RECONCILIATION_MISMATCH))
        );
    }

    @Test
    void reconciliation_detects_missing_entry_ko() {
        // given
        when(reconciliationRepository.findPaymentsWithoutReceipt()).thenReturn(List.of(paymentId));

        // when
        var reconciliation = service.reconcile();

        // then
        assertAll(
            () -> assertThat(reconciliation.isClean()).isFalse(),
            () -> assertThat(reconciliation.getMismatches()).singleElement()
                .satisfies(mismatch -> assertMismatch(mismatch, MismatchCheck.MISSING_ENTRY, "Payment", paymentId))
        );
    }

    @Test
    void reconciliation_detects_unbalanced_transaction_ko() {
        // given
        when(reconciliationRepository.findUnbalancedTransactionIds()).thenReturn(List.of(901L));

        // when
        var reconciliation = service.reconcile();

        // then
        assertAll(
            () -> assertThat(reconciliation.isClean()).isFalse(),
            () -> assertThat(reconciliation.getMismatches()).singleElement()
                .satisfies(mismatch -> assertMismatch(mismatch, MismatchCheck.UNBALANCED_TRANSACTION,
                    "LedgerTransaction", 901L))
        );
    }

    @Test
    void reconciliation_detects_amount_mismatch_ko() {
        // given
        when(reconciliationRepository.findReceiptsWithWrongAmount()).thenReturn(List.of(paymentId));

        // when
        var reconciliation = service.reconcile();

        // then
        assertAll(
            () -> assertThat(reconciliation.isClean()).isFalse(),
            () -> assertThat(reconciliation.getMismatches()).singleElement()
                .satisfies(mismatch -> assertMismatch(mismatch, MismatchCheck.AMOUNT_DISAGREES, "Payment",
                    paymentId))
        );
    }

    private void assertMismatch(Mismatch mismatch, MismatchCheck check, String recordType, long recordId) {
        assertAll(
            () -> assertThat(mismatch.getCheck()).isEqualTo(check),
            () -> assertThat(mismatch.getRecordType()).isEqualTo(recordType),
            () -> assertThat(mismatch.getRecordId()).isEqualTo(recordId)
        );
    }

    private BigDecimal sum(LedgerAccount account, Long orderItemId) {
        return total(savedEntries.stream()
            .filter(entry -> entry.getAccount().equals(account.name()))
            .filter(entry -> orderItemId == null || orderItemId.equals(entry.getOrderItemId()))
            .toList());
    }

    private BigDecimal total(List<LedgerEntry> entries) {
        return entries.stream().map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private ArtistMovement movement(long sourceId, String amount) {
        return ArtistMovement.builder()
            .withSourceId(sourceId)
            .withArtistId(physicalArtistId)
            .withOrderItemId(physicalItemId)
            .withAmount(new BigDecimal(amount))
            .withOccurredAt(now())
            .build();
    }

    // Items subtotal 270 000 = 50 000 + 2 x 110 000; total 305 000 with 5 000 tax and 30 000 shipping.
    private Order paidOrder(BigDecimal digitalFee) {
        var digital = OrderLine.builder()
            .withOrderItemId(digitalItemId)
            .withListingType(ListingType.DIGITAL)
            .withArtistId(digitalArtistId)
            .withQuantity(1)
            .withUnitPrice(new BigDecimal("50000"))
            .withFeeAmount(digitalFee)
            .withArtistProceeds(new BigDecimal("50000").subtract(digitalFee))
            .withTaxAmount(BigDecimal.ZERO)
            .build();
        var physical = OrderLine.builder()
            .withOrderItemId(physicalItemId)
            .withListingType(ListingType.PHYSICAL)
            .withArtistId(physicalArtistId)
            .withQuantity(2)
            .withUnitPrice(new BigDecimal("110000"))
            .withManufacturingCostPerUnit(new BigDecimal("80000"))
            .withMarkupPerUnit(new BigDecimal("30000"))
            .withFeeAmount(BigDecimal.ZERO)
            .withArtistProceeds(new BigDecimal("60000"))
            .withTaxAmount(new BigDecimal("5000"))
            .build();

        return Order.builder()
            .withOrderId(300L)
            .withStatus(OrderStatus.PAID)
            .withPaidAt(now())
            .withShippingAmount(new BigDecimal("30000"))
            .withTaxAmount(new BigDecimal("5000"))
            .withTotalAmount(new BigDecimal("305000"))
            .withItems(List.of(digital, physical))
            .withPayment(PaymentTarget.builder().withPaymentId(paymentId).build())
            .build();
    }

    private ProceedsState proceeds(long proceedsId, long orderItemId, String amount, String status) {
        return new ProceedsState() {
            public Long getProceedsId() {
                return proceedsId;
            }

            public Long getOrderItemId() {
                return orderItemId;
            }

            public BigDecimal getAmount() {
                return new BigDecimal(amount);
            }

            public String getStatus() {
                return status;
            }
        };
    }

    private ItemBalance itemBalance(long orderItemId, String account, String amount) {
        return new ItemBalance() {
            public Long getOrderItemId() {
                return orderItemId;
            }

            public String getAccount() {
                return account;
            }

            public BigDecimal getAmount() {
                return new BigDecimal(amount);
            }
        };
    }

    private AccountTotal accountTotal(String account, String amount) {
        return new AccountTotal() {
            public String getAccount() {
                return account;
            }

            public BigDecimal getAmount() {
                return new BigDecimal(amount);
            }
        };
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
