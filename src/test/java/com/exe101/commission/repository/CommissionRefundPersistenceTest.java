package com.exe101.commission.repository;

import com.exe101.artist.model.TermsPublication;
import com.exe101.artist.repository.ArtistRepository;
import com.exe101.artist.service.ArtistTermsService;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionRequest;
import com.exe101.commission.model.DisputeOutcome;
import com.exe101.commission.model.DisputeResolution;
import com.exe101.commission.model.QuoteTerms;
import com.exe101.commission.service.CommissionCancellationService;
import com.exe101.commission.service.CommissionDisputeService;
import com.exe101.commission.service.CommissionQuoteService;
import com.exe101.commission.service.CommissionService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.StaffAccount;
import com.exe101.ledger.service.LedgerService;
import com.exe101.payment.config.VnpayProperties;
import com.exe101.payment.model.EventOutcome;
import com.exe101.payment.service.PaymentService;
import com.exe101.refund.model.TransferOutcome;
import com.exe101.refund.model.TransferStatus;
import com.exe101.refund.service.RefundService;
import com.exe101.staff.repository.StaffRepository;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

// Commission refunds (F20) move money: the refund agreed with a cancellation is posted to the ledger, the rest of the
// held share is released, and the transfer is recorded by hand. Quote payments commit and cannot be deleted, so this runs only against the
// disposable test database selected by TEST_DB_URL and leaves its rows there. Every payment goes through the IPN, so
// the database stays reconcilable. At a 10% tax rate a price of 100 001 has a tax of 10 000: the first payment is
// 50 001 + 5 000 = 55 001. The scheduled jobs are slowed down so they do not run.
@SpringBootTest(properties = {"app.orders.expiry-interval=PT1H", "app.fulfillment.grant-interval=PT1H",
    "app.checkout.tax-rate=0.10"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class CommissionRefundPersistenceTest {
    private static String firstTimes100 = "5500100";
    private static long blockedMillis = 500;
    private static long waitSeconds = 30;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private CommissionService commissionService;
    @Autowired
    private CommissionQuoteService quoteService;
    @Autowired
    private CommissionCancellationService cancellationService;
    @Autowired
    private CommissionDisputeService disputeService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private VnpayProperties vnpay;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ArtistTermsService termsService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    private Long adminId;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
    }

    @BeforeEach
    void setUp() {
        adminId = admin().getStaffId();
    }

    @Test
    void agreed_refund_amounts_persist_and_reconcile_ok() {
        // given
        var paid = paidCommission();
        var request = cancellationService.request(paid.artistUserId(), paid.commissionId(), "I cannot finish it", 70);
        var refundId = openRefundId(paid);

        // when
        cancellationService.accept(paid.customerId(), paid.commissionId(), request.getCancellationId(), "Agreed");

        // then
        // 70% of 50 001 = 35 000.7 -> 35 001; 70% of 5 000 = 3 500. The Artist keeps 15 000.
        var stored = jdbc.queryForMap("SELECT Status, RefundPercent, DecidedAt FROM dbo.RefundRequest "
            + "WHERE RefundRequestId = ?", refundId);
        var transfer = jdbc.queryForMap("SELECT Amount, Status, PaymentId FROM dbo.RefundTransfer "
            + "WHERE RefundRequestId = ?", refundId);
        var proceeds = jdbc.queryForMap("SELECT Amount, Status FROM dbo.ArtistProceeds WHERE QuoteId = ? "
            + "AND Installment = 1", paid.quoteId());
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(commissionStatus(paid.commissionId())).isEqualTo("CANCELLED"),
            () -> assertThat(stored.get("Status")).isEqualTo("APPROVED"),
            () -> assertThat(((Number) stored.get("RefundPercent")).intValue()).isEqualTo(70),
            () -> assertThat(stored.get("DecidedAt")).isNotNull(),
            () -> assertThat((BigDecimal) transfer.get("Amount")).isEqualByComparingTo("38501"),
            () -> assertThat(transfer.get("Status")).isEqualTo("PENDING"),
            () -> assertThat(((Number) transfer.get("PaymentId")).longValue()).isEqualTo(firstPaymentId(paid)),
            () -> assertThat((BigDecimal) proceeds.get("Amount")).isEqualByComparingTo("15000"),
            () -> assertThat(proceeds.get("Status")).isEqualTo("RESERVED"),
            () -> assertThat(releaseAmounts(paid.quoteId())).singleElement()
                .satisfies(amount -> assertThat(amount).isEqualByComparingTo("15000")),
            () -> assertThat(refundLine(refundId, "ARTIST_HELD", paid.quoteId())).isEqualByComparingTo("35001"),
            () -> assertThat(refundLine(refundId, "TAX_PAYABLE", paid.quoteId())).isEqualByComparingTo("3500"),
            () -> assertThat(refundLine(refundId, "REFUND_PAYABLE", paid.quoteId())).isEqualByComparingTo("-38501"),
            () -> assertThat(artistBalance(paid.quoteId(), "ARTIST_HELD")).isEqualByComparingTo("0"),
            () -> assertThat(artistBalance(paid.quoteId(), "ARTIST_RELEASING")).isEqualByComparingTo("-15000"),
            () -> assertThat(events(paid.commissionId()))
                .endsWith("CANCELLATION_REQUESTED", "REFUND_OFFERED", "CANCELLATION_ACCEPTED"),
            () -> assertThat(reconciliation.getMismatches()).isEmpty()
        );
    }

    @Test
    void customer_request_offer_accept_reconciles_ok() {
        // given
        var paid = paidCommission();
        var request = cancellationService.request(paid.customerId(), paid.commissionId(), "Plans changed", null);
        var offered = cancellationService.offerRefund(paid.artistUserId(), paid.commissionId(),
            request.getCancellationId(), 40);

        // when
        var accepted = cancellationService.accept(paid.customerId(), paid.commissionId(),
            request.getCancellationId(), null);

        // then
        // 40% of 50 001 = 20 000.4 -> 20 000; 40% of 5 000 = 2 000. The Artist keeps 30 001.
        var refundId = openRefundId(paid);
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(offered.getRefundPercent()).isEqualTo(40),
            () -> assertThat(accepted.getRefundPercent()).isEqualTo(40),
            () -> assertThat(commissionStatus(paid.commissionId())).isEqualTo("CANCELLED"),
            () -> assertThat(requestStatus(refundId)).isEqualTo("APPROVED"),
            () -> assertThat(jdbc.queryForObject("SELECT Amount FROM dbo.RefundTransfer WHERE RefundRequestId = ?",
                BigDecimal.class, refundId)).isEqualByComparingTo("22000"),
            () -> assertThat(releaseAmounts(paid.quoteId())).singleElement()
                .satisfies(amount -> assertThat(amount).isEqualByComparingTo("30001")),
            () -> assertThat(reconciliation.getMismatches()).isEmpty()
        );
    }

    @Test
    void refund_transfer_succeeded_posts_refund_paid_ok() {
        // given
        var paid = paidCommission();
        var request = cancellationService.request(paid.artistUserId(), paid.commissionId(), "I cannot finish it", 70);
        var refundId = openRefundId(paid);
        cancellationService.accept(paid.customerId(), paid.commissionId(), request.getCancellationId(), null);
        var transferId = jdbc.queryForObject("SELECT RefundTransferId FROM dbo.RefundTransfer "
            + "WHERE RefundRequestId = ?", Long.class, refundId);
        var reference = "f20-test-" + UUID.randomUUID();

        // when
        var result = refundService.recordTransferOutcome(adminId, transferId, TransferOutcome.builder()
            .withStatus(TransferStatus.SUCCEEDED)
            .withTransferReference(reference)
            .withReason("Sent through the merchant portal")
            .build());

        // then
        var paidLines = jdbc.queryForList("SELECT e.Account, e.Amount, e.QuoteId FROM dbo.LedgerEntry e "
            + "JOIN dbo.LedgerTransaction t ON t.TransactionId = e.TransactionId WHERE t.Type = 'REFUND_PAID' "
            + "AND t.SourceId = ? ORDER BY e.Account", transferId);
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(result.getCommissionId()).isEqualTo(paid.commissionId()),
            () -> assertThat(result.getRefundPercent()).isEqualTo(70),
            () -> assertThat(requestStatus(refundId)).isEqualTo("REFUNDED"),
            () -> assertThat(jdbc.queryForObject("SELECT ProviderRefundRef FROM dbo.RefundTransfer "
                + "WHERE RefundTransferId = ?", String.class, transferId)).isEqualTo(reference),
            () -> assertThat(paidLines).extracting(row -> row.get("Account")).containsExactly("CASH", "REFUND_PAYABLE"),
            () -> assertThat((BigDecimal) paidLines.getFirst().get("Amount")).isEqualByComparingTo("-38501"),
            () -> assertThat(((Number) paidLines.getLast().get("QuoteId")).longValue()).isEqualTo(paid.quoteId()),
            () -> assertThat(reconciliation.getMismatches()).isEmpty()
        );
    }

    @Test
    void full_refund_voids_and_reconciles_ok() {
        // given
        var paid = paidCommission();
        var request = cancellationService.request(paid.artistUserId(), paid.commissionId(), "I am leaving", 100);
        var refundId = openRefundId(paid);

        // when
        cancellationService.accept(paid.customerId(), paid.commissionId(), request.getCancellationId(), null);

        // then
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(jdbc.queryForObject("SELECT Amount FROM dbo.RefundTransfer WHERE RefundRequestId = ?",
                BigDecimal.class, refundId)).isEqualByComparingTo("55001"),
            () -> assertThat(proceedsStatuses(paid.quoteId())).containsExactly("VOID"),
            () -> assertThat(releaseAmounts(paid.quoteId())).isEmpty(),
            () -> assertThat(artistBalance(paid.quoteId(), "ARTIST_HELD")).isEqualByComparingTo("0"),
            () -> assertThat(reconciliation.getMismatches()).isEmpty()
        );
    }

    @Test
    void rejected_offer_keeps_held_and_reconciles_ok() {
        // given
        var paid = paidCommission();
        var request = cancellationService.request(paid.artistUserId(), paid.commissionId(), "I cannot finish it", 10);
        var refundId = openRefundId(paid);

        // when
        cancellationService.reject(paid.customerId(), paid.commissionId(), request.getCancellationId(),
            "Ten percent breaks your terms");

        // then
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(commissionStatus(paid.commissionId())).isEqualTo("IN_PROGRESS"),
            () -> assertThat(requestStatus(refundId)).isEqualTo("REJECTED"),
            () -> assertThat(count("SELECT COUNT(*) FROM dbo.RefundTransfer WHERE RefundRequestId = ?", refundId))
                .isZero(),
            () -> assertThat(proceedsStatuses(paid.quoteId())).containsExactly("HELD"),
            () -> assertThat(artistBalance(paid.quoteId(), "ARTIST_HELD")).isEqualByComparingTo("-50001"),
            () -> assertThat(reconciliation.getMismatches()).isEmpty()
        );
    }

    @Test
    void dispute_cancel_refund_percentage_reconciles_ok() {
        // given
        var paid = paidCommission();
        var dispute = disputeService.open(paid.customerId(), paid.commissionId(), "No progress for two weeks");
        disputeService.review(adminId, dispute.getDisputeId());

        // when
        disputeService.resolve(adminId, dispute.getDisputeId(), DisputeResolution.builder()
            .withOutcome(DisputeOutcome.CANCEL_REFUND)
            .withReason("Only the sketch was delivered")
            .withRefundPercent(40)
            .build());

        // then
        var refund = jdbc.queryForMap("SELECT RefundRequestId, Status, RefundPercent, DecidedByStaffId "
            + "FROM dbo.RefundRequest WHERE CommissionId = ?", paid.commissionId());
        var refundId = ((Number) refund.get("RefundRequestId")).longValue();
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(commissionStatus(paid.commissionId())).isEqualTo("CANCELLED"),
            () -> assertThat(refund.get("Status")).isEqualTo("APPROVED"),
            () -> assertThat(((Number) refund.get("RefundPercent")).intValue()).isEqualTo(40),
            () -> assertThat(((Number) refund.get("DecidedByStaffId")).longValue()).isEqualTo(adminId),
            () -> assertThat(jdbc.queryForObject("SELECT Amount FROM dbo.RefundTransfer WHERE RefundRequestId = ?",
                BigDecimal.class, refundId)).isEqualByComparingTo("22000"),
            () -> assertThat(releaseAmounts(paid.quoteId())).singleElement()
                .satisfies(amount -> assertThat(amount).isEqualByComparingTo("30001")),
            () -> assertThat(jdbc.queryForObject("SELECT Details FROM dbo.AdminAudit WHERE TargetType = "
                + "'COMMISSION_DISPUTE' AND TargetId = ? AND ActionType = 'COMMISSION_DISPUTE_RESOLVED'", String.class,
                dispute.getDisputeId())).endsWith(";refundPercent=40"),
            () -> assertThat(reconciliation.getMismatches()).isEmpty()
        );
    }

    @Test
    void cancel_no_refund_releases_and_reconciles_ok() {
        // given
        var paid = paidCommission();
        var dispute = disputeService.open(paid.customerId(), paid.commissionId(), "No reply for two weeks");
        disputeService.review(adminId, dispute.getDisputeId());

        // when
        disputeService.resolve(adminId, dispute.getDisputeId(), DisputeResolution.builder()
            .withOutcome(DisputeOutcome.CANCEL_NO_REFUND)
            .withReason("The Customer abandoned the work after the sketch")
            .build());

        // then
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(commissionStatus(paid.commissionId())).isEqualTo("CANCELLED"),
            () -> assertThat(count("SELECT COUNT(*) FROM dbo.RefundRequest WHERE CommissionId = ?",
                paid.commissionId())).isZero(),
            () -> assertThat(proceedsStatuses(paid.quoteId())).containsExactly("RESERVED"),
            () -> assertThat(releaseAmounts(paid.quoteId())).singleElement()
                .satisfies(amount -> assertThat(amount).isEqualByComparingTo("50001")),
            () -> assertThat(artistBalance(paid.quoteId(), "ARTIST_HELD")).isEqualByComparingTo("0"),
            () -> assertThat(artistBalance(paid.quoteId(), "ARTIST_RELEASING")).isEqualByComparingTo("-50001"),
            () -> assertThat(reconciliation.getMismatches()).isEmpty()
        );
    }

    @Test
    void concurrent_accept_and_withdraw_one_wins_ok() throws Exception {
        // given
        var paid = paidCommission();
        var cancellationId = cancellationService.request(paid.artistUserId(), paid.commissionId(),
            "I cannot finish it", 70).getCancellationId();
        var refundId = openRefundId(paid);

        // when
        // The Customer's acceptance holds the Commission lock while the Artist's withdrawal waits for it.
        var race = race(
            () -> attemptName(() -> cancellationService.accept(paid.customerId(), paid.commissionId(), cancellationId,
                null)),
            () -> attemptName(() -> cancellationService.withdraw(paid.artistUserId(), paid.commissionId(),
                cancellationId)));

        // then
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(race.blocked()).isTrue(),
            () -> assertThat(race.first()).isNull(),
            () -> assertThat(race.second()).isEqualTo("CANCELLATION_NOT_PENDING"),
            () -> assertThat(commissionStatus(paid.commissionId())).isEqualTo("CANCELLED"),
            () -> assertThat(requestStatus(refundId)).isEqualTo("APPROVED"),
            () -> assertThat(jdbc.queryForList("SELECT Amount FROM dbo.RefundTransfer WHERE RefundRequestId = ?",
                BigDecimal.class, refundId)).singleElement()
                .satisfies(amount -> assertThat(amount).isEqualByComparingTo("38501")),
            () -> assertThat(reconciliation.getMismatches()).isEmpty()
        );
    }

    @Test
    void one_open_refund_per_commission_ko() {
        // given
        var paid = paidCommission();
        cancellationService.request(paid.artistUserId(), paid.commissionId(), "I cannot finish it", 70);

        // when / then
        // UX_Refund_OpenCommission refuses a second open refund; the insert is rolled back either way.
        assertThatThrownBy(() -> rolledBack(() -> jdbc.update("INSERT INTO dbo.RefundRequest (CommissionId, "
            + "RequestedByUserId, Reason, Status, RequestedAt) "
            + "VALUES (?, ?, N'Again', 'REQUESTED', SYSDATETIMEOFFSET())", paid.commissionId(), paid.customerId())))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refund_percent_check_constraint_ko() {
        // given
        var paid = paidCommission();
        cancellationService.request(paid.artistUserId(), paid.commissionId(), "I cannot finish it", 70);
        var refundId = openRefundId(paid);

        // when / then
        assertThatThrownBy(() -> rolledBack(() -> jdbc.update("UPDATE dbo.RefundRequest SET RefundPercent = 101 "
            + "WHERE RefundRequestId = ?", refundId)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT RefundPercent FROM dbo.RefundRequest WHERE RefundRequestId = ?",
            Integer.class, refundId)).isEqualTo(70);
    }

    // Runs the first action in a transaction held open until the second has had time to block on its lock.
    private Race race(Callable<String> first, Callable<String> second) throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> firstResult = executor.submit(() -> inTransaction(() -> {
                var result = first.call();
                started.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return result;
            }));
            assertThat(started.await(waitSeconds, TimeUnit.SECONDS)).isTrue();
            Future<String> secondResult = executor.submit(second);
            var blocked = isBlocked(secondResult);
            release.countDown();

            return new Race(blocked, firstResult.get(waitSeconds, TimeUnit.SECONDS),
                secondResult.get(waitSeconds, TimeUnit.SECONDS));
        }
    }

    // The Commission's one refund: the offer while its cancellation is pending, the settlement once answered.
    private Long openRefundId(Accepted paid) {
        return jdbc.queryForObject("SELECT TOP 1 RefundRequestId FROM dbo.RefundRequest WHERE CommissionId = ? "
            + "ORDER BY RefundRequestId DESC", Long.class, paid.commissionId());
    }

    // The first installment is paid through the IPN, so the Commission is IN_PROGRESS.
    private Accepted paidCommission() {
        var accepted = acceptedQuote();
        var start = paymentService.startQuote(accepted.customerId(), accepted.commissionId(), accepted.quoteId(),
            "203.0.113.7");
        var paid = paymentService.handleVnpayNotification(success(attemptRef(start.getAttemptId()), firstTimes100));
        assertThat(paid.getOutcome()).isEqualTo(EventOutcome.PROCESSED);

        return accepted;
    }

    private Accepted acceptedQuote() {
        var customerId = newUser();
        var artistUserId = newUser();
        var artist = artistRepository.saveAndFlush(new ArtistProfile(artistUserId, "Painter", "ACTIVE", true, adminId,
            now(), null));
        var artistTerms = termsService.publish(artistUserId, TermsPublication.builder()
            .withText("No refund after the lineart")
            .build());
        Commission commission = commissionService.request(customerId, CommissionRequest.builder()
            .withArtistId(artist.getArtistId())
            .withDescription("Portrait of my cat")
            .withAllowPublicDisplay(false)
            .withUploads(List.of())
            .build());
        var quote = quoteService.offer(artistUserId, commission.getCommissionId(), QuoteTerms.builder()
            .withPrice(new BigDecimal("100001"))
            .withScopeText("Full-body portrait, one character")
            .withExpectedCompletionTerms("Within 3 weeks of the first payment")
            .build());
        quoteService.accept(customerId, commission.getCommissionId(), quote.getQuoteId(),
            artistTerms.getArtistTermsId());

        return new Accepted(customerId, artistUserId, artist.getArtistId(), commission.getCommissionId(),
            quote.getQuoteId());
    }

    // The error a rejected action reports, or null when it succeeded.
    private String attemptName(Runnable action) {
        try {
            action.run();
            return null;
        } catch (AppException exception) {
            AppErrorMessage error = exception.getError();
            return error.name();
        }
    }

    private Long firstPaymentId(Accepted paid) {
        return jdbc.queryForObject("SELECT PaymentId FROM dbo.Payment WHERE QuoteId = ? AND Installment = 1",
            Long.class, paid.quoteId());
    }

    private BigDecimal refundLine(Long refundId, String account, Long quoteId) {
        return jdbc.queryForObject("SELECT SUM(e.Amount) FROM dbo.LedgerEntry e JOIN dbo.LedgerTransaction t "
            + "ON t.TransactionId = e.TransactionId WHERE t.Type = 'REFUND_APPROVED' AND t.SourceId = ? "
            + "AND e.Account = ? AND e.QuoteId = ?", BigDecimal.class, refundId, account, quoteId);
    }

    private List<BigDecimal> releaseAmounts(Long quoteId) {
        return jdbc.queryForList("SELECT l.Amount FROM dbo.PayoutLine l JOIN dbo.ArtistProceeds a "
            + "ON a.ProceedsId = l.ProceedsId WHERE a.QuoteId = ?", BigDecimal.class, quoteId);
    }

    private List<Object> proceedsStatuses(Long quoteId) {
        return jdbc.queryForList("SELECT Status FROM dbo.ArtistProceeds WHERE QuoteId = ? ORDER BY Installment",
            Object.class, quoteId);
    }

    private BigDecimal artistBalance(Long quoteId, String account) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(Amount), 0) FROM dbo.LedgerEntry WHERE QuoteId = ? "
            + "AND Account = ?", BigDecimal.class, quoteId, account);
    }

    private String requestStatus(Long refundId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.RefundRequest WHERE RefundRequestId = ?", String.class,
            refundId);
    }

    private String commissionStatus(Long commissionId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.Commission WHERE CommissionId = ?", String.class,
            commissionId);
    }

    private List<String> events(Long commissionId) {
        return jdbc.queryForList("SELECT EventType FROM dbo.CommissionEvent WHERE CommissionId = ? ORDER BY EventId",
            String.class, commissionId);
    }

    private String attemptRef(long attemptId) {
        return jdbc.queryForObject("SELECT ProviderAttemptRef FROM dbo.PaymentAttempt WHERE AttemptId = ?",
            String.class, attemptId);
    }

    // A success for one VNPay transaction per attempt, signed as VNPay signs it.
    private Map<String, String> success(String ref, String amountTimes100) {
        var fields = new TreeMap<String, String>();
        fields.put("vnp_TmnCode", vnpay.tmnCode());
        fields.put("vnp_Amount", amountTimes100);
        fields.put("vnp_BankCode", "NCB");
        fields.put("vnp_PayDate", "20261102103000");
        fields.put("vnp_ResponseCode", "00");
        fields.put("vnp_TransactionNo", String.valueOf(Math.abs(ref.hashCode())));
        fields.put("vnp_TransactionStatus", "00");
        fields.put("vnp_TxnRef", ref);
        var query = fields.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.US_ASCII))
            .collect(Collectors.joining("&"));
        var parameters = new HashMap<String, String>(fields);
        parameters.put("vnp_SecureHash", hmac(query));

        return parameters;
    }

    private String hmac(String data) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(vnpay.hashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));

            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Integer count(String sql, Object id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private Long newUser() {
        return customerService.register(new CustomerRegistration(
            "f20-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Member")).getUserId();
    }

    private <T> T inTransaction(Callable<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    // Constraint checks write nothing: the transaction is always rolled back.
    private void rolledBack(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            status.setRollbackOnly();
            work.run();
        });
    }

    // True when the work is still waiting for a lock after a short time.
    private boolean isBlocked(Future<?> work) throws Exception {
        try {
            work.get(blockedMillis, TimeUnit.MILLISECONDS);
            return false;
        } catch (TimeoutException exception) {
            return true;
        } catch (ExecutionException exception) {
            return false;
        }
    }

    private StaffAccount admin() {
        return staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive())
            .findFirst()
            .orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount("f20-test-" + UUID.randomUUID(),
                "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now())));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private record Accepted(Long customerId, Long artistUserId, Long artistId, Long commissionId, Long quoteId) {
    }

    private record Race(boolean blocked, String first, String second) {
    }
}
