package com.exe101.commission.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionRequest;
import com.exe101.commission.model.DeliverableSubmission;
import com.exe101.commission.model.DisputeOutcome;
import com.exe101.commission.model.DisputeResolution;
import com.exe101.commission.model.FileUpload;
import com.exe101.commission.model.QuoteTerms;
import com.exe101.commission.service.CommissionCancellationService;
import com.exe101.commission.service.CommissionDeliveryService;
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
import com.exe101.payment.model.NotificationResult;
import com.exe101.payment.service.PaymentService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

// Cancellation and disputes (F19) move no money: paid installments stay HELD while the Commission's refund (F20) is
// open, and a payment landing on a cancelled Commission is an exception booked to UNAPPLIED. Quote payments commit,
// and Quotes, versions and cancellation history can never be deleted, so this runs only against the disposable test
// database selected by TEST_DB_URL and leaves its rows there. Every payment goes through the IPN, so the database stays
// reconcilable. At a 10% tax rate a price of 100 001 has a tax of 10 000: the first payment is 50 001 + 5 000 =
// 55 001 and the final one 50 000 + 5 000 = 55 000. The scheduled jobs are slowed down so they do not run.
@SpringBootTest(properties = {"app.orders.expiry-interval=PT1H", "app.fulfillment.grant-interval=PT1H",
    "app.checkout.tax-rate=0.10"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class CommissionCancellationPersistenceTest {
    private static String firstTimes100 = "5500100";
    private static String finalTimes100 = "5500000";
    private static long blockedMillis = 500;
    private static long waitSeconds = 30;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private CommissionService commissionService;
    @Autowired
    private CommissionQuoteService quoteService;
    @Autowired
    private CommissionDeliveryService deliveryService;
    @Autowired
    private CommissionCancellationService cancellationService;
    @Autowired
    private CommissionDisputeService disputeService;
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
    void concurrent_dispute_open_one_wins_ok() throws Exception {
        // given
        var paid = paidCommission();
        var opened = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The Customer's dispute is opened and held uncommitted while the Artist opens another.
            Future<AppErrorMessage> first = executor.submit(() -> inTransaction(() -> {
                var error = attempt(() -> disputeService.open(paid.customerId(), paid.commissionId(),
                    "No progress for two weeks"));
                opened.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return error;
            }));
            assertThat(opened.await(waitSeconds, TimeUnit.SECONDS)).isTrue();
            Future<AppErrorMessage> second = executor.submit(() -> attempt(() ->
                disputeService.open(paid.artistUserId(), paid.commissionId(), "The Customer will not answer")));
            var blocked = isBlocked(second);
            release.countDown();
            var firstError = first.get(waitSeconds, TimeUnit.SECONDS);
            var secondError = second.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(blocked).isTrue(),
                () -> assertThat(firstError).isNull(),
                () -> assertThat(secondError).isEqualTo(AppErrorMessage.DISPUTE_ALREADY_OPEN),
                () -> assertThat(count("SELECT COUNT(*) FROM dbo.CommissionDispute WHERE CommissionId = ?",
                    paid.commissionId())).isEqualTo(1),
                () -> assertThat(commissionStatus(paid.commissionId())).isEqualTo("DISPUTED"),
                () -> assertThat(events(paid.commissionId())).filteredOn("DISPUTE_OPENED"::equals).hasSize(1)
            );
        }
    }

    @Test
    void accept_cancellation_vs_final_payment_one_wins_ok() throws Exception {
        // given
        // On each Commission the Customer opened the final payment page before the Artist asked to cancel.
        var paymentFirst = approvedCommission();
        var paymentFirstRef = startFinal(paymentFirst);
        var paymentFirstRequest = cancellationService.request(paymentFirst.artistUserId(), paymentFirst.commissionId(),
            "I cannot deliver the print rights").getCancellationId();
        var acceptFirst = approvedCommission();
        var acceptFirstRef = startFinal(acceptFirst);
        var acceptFirstRequest = cancellationService.request(acceptFirst.artistUserId(), acceptFirst.commissionId(),
            "I cannot deliver the print rights").getCancellationId();

        // when
        // The payment holds the Commission lock while the acceptance waits for it, and then the other way round.
        var paymentWins = race(
            () -> outcome(paymentService.handleVnpayNotification(success(paymentFirstRef, finalTimes100))),
            () -> attemptName(() -> cancellationService.accept(paymentFirst.customerId(), paymentFirst.commissionId(),
                paymentFirstRequest, null)));
        var acceptWins = race(
            () -> attemptName(() -> cancellationService.accept(acceptFirst.customerId(), acceptFirst.commissionId(),
                acceptFirstRequest, null)),
            () -> outcome(paymentService.handleVnpayNotification(success(acceptFirstRef, finalTimes100))));

        // then
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(paymentWins.blocked()).isTrue(),
            () -> assertThat(paymentWins.first()).isEqualTo("PROCESSED"),
            () -> assertThat(paymentWins.second()).isEqualTo("CANCELLATION_NOT_PENDING"),
            () -> assertThat(commissionStatus(paymentFirst.commissionId())).isEqualTo("COMPLETED"),
            () -> assertThat(cancellationStatus(paymentFirstRequest)).isEqualTo("REJECTED"),
            () -> assertThat(events(paymentFirst.commissionId())).contains("CANCELLATION_CLOSED"),
            () -> assertThat(proceedsStatuses(paymentFirst.quoteId())).containsExactly("RESERVED", "RESERVED"),
            () -> assertThat(acceptWins.blocked()).isTrue(),
            () -> assertThat(acceptWins.first()).isNull(),
            () -> assertThat(acceptWins.second()).isEqualTo("EXCEPTION"),
            () -> assertThat(commissionStatus(acceptFirst.commissionId())).isEqualTo("CANCELLED"),
            () -> assertThat(cancellationStatus(acceptFirstRequest)).isEqualTo("ACCEPTED"),
            () -> assertThat(paymentStatus(acceptFirst.quoteId(), 2)).isEqualTo("EXCEPTION"),
            () -> assertThat(proceedsStatuses(acceptFirst.quoteId())).containsExactly("HELD"),
            () -> assertThat(unappliedCount(acceptFirstRef)).isEqualTo(1),
            () -> assertThat(reconciliation.getMismatches()).isEmpty()
        );
    }

    @Test
    void cancel_paid_commission_keeps_proceeds_held_ok() {
        // given
        var paid = paidCommission();
        var request = cancellationService.request(paid.customerId(), paid.commissionId(), "Plans changed");

        // when
        cancellationService.accept(paid.artistUserId(), paid.commissionId(), request.getCancellationId(), "Agreed");

        // then
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(commissionStatus(paid.commissionId())).isEqualTo("CANCELLED"),
            () -> assertThat(cancellationStatus(request.getCancellationId())).isEqualTo("ACCEPTED"),
            () -> assertThat(proceedsStatuses(paid.quoteId())).containsExactly("HELD"),
            () -> assertThat(releaseCount(paid.quoteId())).isZero(),
            // F20 opens the refund; the money stays HELD until it is decided.
            () -> assertThat(count("SELECT COUNT(*) FROM dbo.RefundRequest WHERE CommissionId = ? "
                + "AND Status = 'REQUESTED'", paid.commissionId())).isEqualTo(1),
            () -> assertThat(artistBalance(paid.quoteId(), "ARTIST_HELD")).isEqualByComparingTo("-50001"),
            () -> assertThat(paymentStatus(paid.quoteId(), 1)).isEqualTo("SUCCEEDED"),
            () -> assertThat(reconciliation.getMismatches()).isEmpty()
        );
    }

    @Test
    void late_success_after_prepayment_cancel_is_unapplied_ok() {
        // given
        // The Customer opened the first payment page and then cancelled before paying.
        var accepted = acceptedQuote();
        var start = paymentService.startQuote(accepted.customerId(), accepted.commissionId(), accepted.quoteId(),
            "203.0.113.7");
        var ref = attemptRef(start.getAttemptId());
        cancellationService.cancel(accepted.customerId(), accepted.commissionId(), "Found another artist");

        // when
        var result = paymentService.handleVnpayNotification(success(ref, firstTimes100));

        // then
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(result.getOutcome()).isEqualTo(EventOutcome.EXCEPTION),
            () -> assertThat(commissionStatus(accepted.commissionId())).isEqualTo("CANCELLED"),
            () -> assertThat(paymentStatus(accepted.quoteId(), 1)).isEqualTo("EXCEPTION"),
            () -> assertThat(proceedsStatuses(accepted.quoteId())).isEmpty(),
            () -> assertThat(unappliedCount(ref)).isEqualTo(1),
            () -> assertThat(reconciliation.getMismatches()).isEmpty()
        );
    }

    @Test
    void dispute_cancel_outcome_keeps_proceeds_held_ok() {
        // given
        var paid = paidCommission();
        var dispute = disputeService.open(paid.customerId(), paid.commissionId(), "No progress for two weeks");
        disputeService.review(adminId, dispute.getDisputeId());

        // when
        var resolved = disputeService.resolve(adminId, dispute.getDisputeId(), DisputeResolution.builder()
            .withOutcome(DisputeOutcome.CANCEL_REFUND)
            .withReason("The Artist left the team")
            .build());

        // then
        var audits = jdbc.queryForList("SELECT ActionType, StaffId, Reason FROM dbo.AdminAudit "
            + "WHERE TargetType = 'COMMISSION_DISPUTE' AND TargetId = ? ORDER BY AuditId", dispute.getDisputeId());
        var stored = jdbc.queryForMap("SELECT Status, Outcome, DecidedByStaffId, ResolvedAt FROM dbo.CommissionDispute "
            + "WHERE DisputeId = ?", dispute.getDisputeId());
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(resolved.getResolutionReason()).isEqualTo("The Artist left the team"),
            () -> assertThat(stored.get("Status")).isEqualTo("RESOLVED"),
            () -> assertThat(stored.get("Outcome")).isEqualTo("CANCEL_REFUND"),
            () -> assertThat(((Number) stored.get("DecidedByStaffId")).longValue()).isEqualTo(adminId),
            () -> assertThat(stored.get("ResolvedAt")).isNotNull(),
            () -> assertThat(commissionStatus(paid.commissionId())).isEqualTo("CANCELLED"),
            () -> assertThat(events(paid.commissionId()))
                .endsWith("DISPUTE_OPENED", "DISPUTE_UNDER_REVIEW", "DISPUTE_RESOLVED", "REFUND_OPENED"),
            () -> assertThat(audits).extracting(row -> row.get("ActionType"))
                .containsExactly("COMMISSION_DISPUTE_REVIEW_STARTED", "COMMISSION_DISPUTE_RESOLVED"),
            () -> assertThat(audits).extracting(row -> ((Number) row.get("StaffId")).longValue()).containsOnly(adminId),
            () -> assertThat(audits.getLast().get("Reason")).isEqualTo("The Artist left the team"),
            () -> assertThat(proceedsStatuses(paid.quoteId())).containsExactly("HELD"),
            () -> assertThat(releaseCount(paid.quoteId())).isZero(),
            () -> assertThat(artistBalance(paid.quoteId(), "ARTIST_HELD")).isEqualByComparingTo("-50001"),
            () -> assertThat(reconciliation.getMismatches()).isEmpty()
        );
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

    // The first installment is paid through the IPN, so the Commission is IN_PROGRESS.
    private Accepted paidCommission() {
        var accepted = acceptedQuote();
        var start = paymentService.startQuote(accepted.customerId(), accepted.commissionId(), accepted.quoteId(),
            "203.0.113.7");
        var paid = paymentService.handleVnpayNotification(success(attemptRef(start.getAttemptId()), firstTimes100));
        assertThat(paid.getOutcome()).isEqualTo(EventOutcome.PROCESSED);

        return accepted;
    }

    // A paid Commission with one version submitted and approved: the final 50% is due.
    private Accepted approvedCommission() {
        var paid = paidCommission();
        var version = deliveryService.submit(paid.artistUserId(), paid.commissionId(), DeliverableSubmission.builder()
            .withNotes("Final render")
            .withUploads(List.of(FileUpload.builder()
                .withOriginalName("cat.png")
                .withBytes(new byte[] {1, 2, 3})
                .build()))
            .build());
        deliveryService.approve(paid.customerId(), paid.commissionId(), version.getDeliverableId());

        return paid;
    }

    private Accepted acceptedQuote() {
        var customerId = newUser();
        var artistUserId = newUser();
        var artist = artistRepository.saveAndFlush(new ArtistProfile(artistUserId, "Painter", "ACTIVE", true, adminId,
            now(), null));
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
        quoteService.accept(customerId, commission.getCommissionId(), quote.getQuoteId());

        return new Accepted(customerId, artistUserId, artist.getArtistId(), commission.getCommissionId(),
            quote.getQuoteId());
    }

    private String startFinal(Accepted approved) {
        var start = paymentService.startQuote(approved.customerId(), approved.commissionId(), approved.quoteId(),
            "203.0.113.7");
        assertThat(start.getInstallment()).isEqualTo(2);

        return attemptRef(start.getAttemptId());
    }

    private String outcome(NotificationResult result) {
        return result.getOutcome().name();
    }

    // The error a rejected action reports, or null when it succeeded.
    private AppErrorMessage attempt(Runnable action) {
        try {
            action.run();
            return null;
        } catch (AppException exception) {
            return exception.getError();
        }
    }

    private String attemptName(Runnable action) {
        var error = attempt(action);

        return error == null ? null : error.name();
    }

    private List<Object> proceedsStatuses(Long quoteId) {
        return jdbc.queryForList("SELECT Status FROM dbo.ArtistProceeds WHERE QuoteId = ? ORDER BY Installment",
            Object.class, quoteId);
    }

    private Integer releaseCount(Long quoteId) {
        return count("SELECT COUNT(*) FROM dbo.PayoutLine l JOIN dbo.ArtistProceeds a ON a.ProceedsId = l.ProceedsId "
            + "WHERE a.QuoteId = ?", quoteId);
    }

    private Integer unappliedCount(String ref) {
        return count("SELECT COUNT(*) FROM dbo.LedgerTransaction t JOIN dbo.ProviderEvent e "
            + "ON e.ProviderEventId = t.SourceId JOIN dbo.PaymentAttempt a ON a.AttemptId = e.AttemptId "
            + "WHERE t.Type = 'PAYMENT_UNAPPLIED' AND a.ProviderAttemptRef = ?", ref);
    }

    private BigDecimal artistBalance(Long quoteId, String account) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(Amount), 0) FROM dbo.LedgerEntry WHERE QuoteId = ? "
            + "AND Account = ?", BigDecimal.class, quoteId, account);
    }

    private String paymentStatus(Long quoteId, int installment) {
        return jdbc.queryForObject("SELECT Status FROM dbo.Payment WHERE QuoteId = ? AND Installment = ?",
            String.class, quoteId, installment);
    }

    private String cancellationStatus(Long cancellationId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.CancellationRequest WHERE CancellationId = ?",
            String.class, cancellationId);
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

    private String commissionStatus(Long commissionId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.Commission WHERE CommissionId = ?", String.class,
            commissionId);
    }

    private List<String> events(Long commissionId) {
        return jdbc.queryForList("SELECT EventType FROM dbo.CommissionEvent WHERE CommissionId = ? ORDER BY EventId",
            String.class, commissionId);
    }

    private Integer count(String sql, Object id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private Long newUser() {
        return customerService.register(new CustomerRegistration(
            "f19-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Member")).getUserId();
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
            .orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount("f19-test-" + UUID.randomUUID(),
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
