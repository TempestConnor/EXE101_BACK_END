package com.exe101.commission.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionRequest;
import com.exe101.commission.model.DeliverableSubmission;
import com.exe101.commission.model.FileUpload;
import com.exe101.commission.model.QuoteTerms;
import com.exe101.commission.service.CommissionDeliveryService;
import com.exe101.commission.service.CommissionQuoteService;
import com.exe101.commission.service.CommissionService;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.StaffAccount;
import com.exe101.ledger.model.Mismatch;
import com.exe101.ledger.model.MismatchCheck;
import com.exe101.ledger.service.LedgerService;
import com.exe101.payment.config.VnpayProperties;
import com.exe101.payment.model.EventOutcome;
import com.exe101.payment.model.NotificationResult;
import com.exe101.payment.model.VnpayReply;
import com.exe101.payment.service.PaymentService;
import com.exe101.proceeds.model.ReleaseOutcome;
import com.exe101.proceeds.model.ReleaseStatus;
import com.exe101.proceeds.service.ProceedsService;
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
import static org.assertj.core.groups.Tuple.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;

// The final Quote payment, completion and the release of the Quote's proceeds (F18b). Quote payments commit, and
// Quotes and versions can never be deleted, so this runs only against the disposable test database selected by
// TEST_DB_URL and leaves its rows there. Every Commission is paid through the IPN, so each money event writes its
// ledger transaction and the database stays reconcilable. At a 10% tax rate a price of 100 001 has a tax of 10 000:
// the first payment is 50 001 + 5 000 = 55 001 and the final one 50 000 + 5 000 = 55 000. The scheduled jobs are
// slowed down so they do not run during a test.
@SpringBootTest(properties = {"app.orders.expiry-interval=PT1H", "app.fulfillment.grant-interval=PT1H",
    "app.checkout.tax-rate=0.10"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class CommissionCompletionPersistenceTest {
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
    private ProceedsService proceedsService;
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
    void final_payment_completes_and_reconciles_ok() {
        // given
        var approved = approvedCommission("100001");
        var ref = startFinal(approved);

        // when
        var result = paymentService.handleVnpayNotification(success(ref, finalTimes100));

        // then
        var payment = jdbc.queryForMap("SELECT PaymentId, Amount, Status, SucceededAt FROM dbo.Payment "
            + "WHERE QuoteId = ? AND Installment = 2", approved.quoteId());
        var paymentId = ((Number) payment.get("PaymentId")).longValue();
        var quote = jdbc.queryForMap("SELECT Status, PaidAt FROM dbo.CommissionQuote WHERE QuoteId = ?",
            approved.quoteId());
        var commission = jdbc.queryForMap("SELECT Status, CompletedAt FROM dbo.Commission WHERE CommissionId = ?",
            approved.commissionId());
        var lines = jdbc.queryForList("SELECT e.Account, e.QuoteId, e.Amount FROM dbo.LedgerEntry e "
            + "JOIN dbo.LedgerTransaction t ON t.TransactionId = e.TransactionId "
            + "WHERE t.Type = 'PAYMENT_RECEIVED' AND t.SourceType = 'PAYMENT' AND t.SourceId = ?", paymentId);
        var proceeds = quoteProceeds(approved.quoteId());
        var releases = quoteReleases(approved.quoteId());
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(result.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(result.getOutcome()).isEqualTo(EventOutcome.PROCESSED),
            () -> assertThat((BigDecimal) payment.get("Amount")).isEqualByComparingTo("55000"),
            () -> assertThat(payment.get("Status")).isEqualTo("SUCCEEDED"),
            () -> assertThat(payment.get("SucceededAt")).isNotNull(),
            () -> assertThat(quote.get("Status")).isEqualTo("PAID"),
            () -> assertThat(quote.get("PaidAt")).isNotNull(),
            () -> assertThat(commission.get("Status")).isEqualTo("COMPLETED"),
            () -> assertThat(commission.get("CompletedAt")).isNotNull(),
            () -> assertThat(events(approved.commissionId())).endsWith("DELIVERABLE_APPROVED", "PAYMENT_CONFIRMED"),
            () -> assertThat(lines).hasSize(3),
            () -> assertThat((BigDecimal) line(lines, "CASH").get("Amount")).isEqualByComparingTo("55000"),
            () -> assertThat((BigDecimal) line(lines, "ARTIST_HELD").get("Amount")).isEqualByComparingTo("-50000"),
            () -> assertThat((BigDecimal) line(lines, "TAX_PAYABLE").get("Amount")).isEqualByComparingTo("-5000"),
            () -> assertThat(proceeds).extracting(row -> row.get("Status")).containsExactly("RESERVED", "RESERVED"),
            () -> assertThat(proceeds).extracting(row -> row.get("EligibleAt")).doesNotContainNull(),
            () -> assertThat(releases).extracting(row -> ((BigDecimal) row.get("Amount")).toBigInteger().intValue())
                .containsExactly(50001, 50000),
            () -> assertThat(releases).extracting(row -> row.get("Status")).containsOnly("PENDING"),
            () -> assertThat(releases).extracting(row -> ((Number) row.get("ArtistId")).longValue())
                .containsOnly(approved.artistId()),
            () -> assertThat(artistBalance(approved.quoteId(), "ARTIST_HELD")).isEqualByComparingTo("0"),
            () -> assertThat(artistBalance(approved.quoteId(), "ARTIST_RELEASING")).isEqualByComparingTo("-100001"),
            () -> assertThat(reconciliation.getMismatches()).isEmpty(),
            () -> assertThat(reconciliation.isClean()).isTrue()
        );
    }

    @Test
    void final_payment_duplicate_ipn_ko() {
        // given
        var approved = approvedCommission("100001");
        var success = success(startFinal(approved), finalTimes100);

        // when
        var first = paymentService.handleVnpayNotification(success);
        var second = paymentService.handleVnpayNotification(success);

        // then
        assertAll(
            () -> assertThat(first.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(second.isDuplicate()).isTrue(),
            () -> assertThat(commissionStatus(approved.commissionId())).isEqualTo("COMPLETED"),
            () -> assertThat(events(approved.commissionId())).filteredOn("PAYMENT_CONFIRMED"::equals).hasSize(2),
            () -> assertThat(count("SELECT COUNT(*) FROM dbo.LedgerTransaction t JOIN dbo.Payment p "
                + "ON p.PaymentId = t.SourceId WHERE t.Type = 'PAYMENT_RECEIVED' AND p.QuoteId = ?",
                approved.quoteId())).isEqualTo(2),
            () -> assertThat(count("SELECT COUNT(*) FROM dbo.ArtistProceeds WHERE QuoteId = ?", approved.quoteId()))
                .isEqualTo(2),
            () -> assertThat(quoteReleases(approved.quoteId())).hasSize(2),
            () -> assertThat(ledgerService.reconcile().isClean()).isTrue()
        );
    }

    @Test
    void final_payment_concurrent_successes_ko() throws Exception {
        // given
        // Two attempts for the same final payment both succeed at VNPay.
        var approved = approvedCommission("100001");
        var firstRef = startFinal(approved);
        var secondRef = startFinal(approved);
        var handled = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The first success is handled and held uncommitted while the second arrives.
            Future<NotificationResult> first = executor.submit(() -> inTransaction(() -> {
                var result = paymentService.handleVnpayNotification(success(firstRef, finalTimes100));
                handled.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return result;
            }));
            assertThat(handled.await(waitSeconds, TimeUnit.SECONDS)).isTrue();
            Future<NotificationResult> second = executor.submit(() ->
                paymentService.handleVnpayNotification(success(secondRef, finalTimes100)));
            var blocked = isBlocked(second);
            release.countDown();
            var firstResult = first.get(waitSeconds, TimeUnit.SECONDS);
            var secondResult = second.get(waitSeconds, TimeUnit.SECONDS);

            // then
            var reconciliation = ledgerService.reconcile();
            assertAll(
                () -> assertThat(blocked).isTrue(),
                () -> assertThat(firstResult.getOutcome()).isEqualTo(EventOutcome.PROCESSED),
                () -> assertThat(secondResult.getOutcome()).isEqualTo(EventOutcome.EXCEPTION),
                () -> assertThat(attemptStatus(firstRef)).isEqualTo("SUCCEEDED"),
                () -> assertThat(attemptStatus(secondRef)).isEqualTo("EXCEPTION"),
                () -> assertThat(commissionStatus(approved.commissionId())).isEqualTo("COMPLETED"),
                () -> assertThat(count("SELECT COUNT(*) FROM dbo.Payment WHERE QuoteId = ? AND Installment = 2",
                    approved.quoteId())).isEqualTo(1),
                () -> assertThat(count("SELECT COUNT(*) FROM dbo.ArtistProceeds WHERE QuoteId = ?",
                    approved.quoteId())).isEqualTo(2),
                () -> assertThat(quoteReleases(approved.quoteId())).hasSize(2),
                () -> assertThat(count("SELECT COUNT(*) FROM dbo.LedgerTransaction t JOIN dbo.ProviderEvent e "
                    + "ON e.ProviderEventId = t.SourceId JOIN dbo.PaymentAttempt a ON a.AttemptId = e.AttemptId "
                    + "WHERE t.Type = 'PAYMENT_UNAPPLIED' AND a.ProviderAttemptRef = ?", secondRef)).isEqualTo(1),
                () -> assertThat(reconciliation.getMismatches()).isEmpty()
            );
        }
    }

    @Test
    void final_payment_release_paid_reconciles_ok() {
        // given
        var approved = approvedCommission("100001");
        paymentService.handleVnpayNotification(success(startFinal(approved), finalTimes100));
        var payoutId = ((Number) quoteReleases(approved.quoteId()).getFirst().get("PayoutId")).longValue();
        var reference = "F18B-" + UUID.randomUUID();

        // when
        var release = proceedsService.recordOutcome(adminId, payoutId, ReleaseOutcome.builder()
            .withStatus(ReleaseStatus.PAID)
            .withTransferReference(reference)
            .withReason("Transferred")
            .build());

        // then
        var paidLines = jdbc.queryForList("SELECT e.Account, e.QuoteId, e.OrderItemId, e.Amount FROM dbo.LedgerEntry e "
            + "JOIN dbo.LedgerTransaction t ON t.TransactionId = e.TransactionId "
            + "WHERE t.Type = 'RELEASE_PAID' AND t.SourceId = ?", payoutId);
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(release.getStatus()).isEqualTo(ReleaseStatus.PAID),
            () -> assertThat(quoteProceeds(approved.quoteId())).extracting(row -> row.get("Status"))
                .containsExactly("PAID", "RESERVED"),
            () -> assertThat((BigDecimal) line(paidLines, "ARTIST_RELEASING").get("Amount"))
                .isEqualByComparingTo("50001"),
            () -> assertThat(((Number) line(paidLines, "ARTIST_RELEASING").get("QuoteId")).longValue())
                .isEqualTo(approved.quoteId()),
            () -> assertThat(line(paidLines, "ARTIST_RELEASING").get("OrderItemId")).isNull(),
            () -> assertThat((BigDecimal) line(paidLines, "CASH").get("Amount")).isEqualByComparingTo("-50001"),
            () -> assertThat(reconciliation.getMismatches()).isEmpty()
        );
    }

    @Test
    void released_quote_proceeds_without_entry_ko() {
        // given
        var accepted = acceptedQuote("100001");
        var insert = "INSERT INTO dbo.ArtistProceeds (ArtistId, OrderItemId, QuoteId, Installment, Amount, "
            + "CurrencyCode, Status, EligibleAt) VALUES (?, NULL, ?, 1, 50001, 'VND', 'RESERVED', SYSDATETIMEOFFSET())";

        // when / then
        // Nothing here commits: the check runs in a transaction that is rolled back.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update(insert, accepted.artistId(), accepted.quoteId());
            var proceedsId = jdbc.queryForObject("SELECT ProceedsId FROM dbo.ArtistProceeds WHERE QuoteId = ?",
                Long.class, accepted.quoteId());
            var mismatches = ledgerService.reconcile().getMismatches();
            assertThat(mismatches).filteredOn(mismatch -> mismatch.getRecordId().equals(proceedsId))
                .extracting(Mismatch::getCheck, Mismatch::getRecordType)
                .contains(tuple(MismatchCheck.MISSING_ENTRY, "ArtistProceeds"));
            status.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM dbo.ArtistProceeds WHERE QuoteId = ?", accepted.quoteId())).isZero();
    }

    @Test
    void zero_final_approval_completes_ok() {
        // given
        // Price 1 has a tax of 0 at 10%: the first payment is 1 VND and the final one 0 VND, which cannot be paid.
        var submitted = submittedCommission("1");

        // when
        var result = deliveryService.approve(submitted.customerId(), submitted.commissionId(),
            submitted.deliverableId());

        // then
        var quote = jdbc.queryForMap("SELECT Status, PaidAt FROM dbo.CommissionQuote WHERE QuoteId = ?",
            submitted.quoteId());
        var releases = quoteReleases(submitted.quoteId());
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(result.getStatus().name()).isEqualTo("COMPLETED"),
            () -> assertThat(commissionStatus(submitted.commissionId())).isEqualTo("COMPLETED"),
            () -> assertThat(quote.get("Status")).isEqualTo("PAID"),
            () -> assertThat(quote.get("PaidAt")).isNotNull(),
            () -> assertThat(count("SELECT COUNT(*) FROM dbo.Payment WHERE QuoteId = ? AND Installment = 2",
                submitted.quoteId())).isZero(),
            () -> assertThat(quoteProceeds(submitted.quoteId())).extracting(row -> row.get("Status"))
                .containsExactly("RESERVED"),
            () -> assertThat(releases).singleElement().satisfies(row ->
                assertThat((BigDecimal) row.get("Amount")).isEqualByComparingTo("1")),
            () -> assertThat(reconciliation.getMismatches()).isEmpty()
        );
    }

    // An accepted Quote whose first installment is paid through the IPN, with one version submitted and approved.
    private Submitted approvedCommission(String price) {
        var submitted = submittedCommission(price);
        deliveryService.approve(submitted.customerId(), submitted.commissionId(), submitted.deliverableId());

        return submitted;
    }

    private Submitted submittedCommission(String price) {
        var accepted = acceptedQuote(price);
        var start = paymentService.startQuote(accepted.customerId(), accepted.commissionId(), accepted.quoteId(),
            "203.0.113.7");
        var paid = paymentService.handleVnpayNotification(success(attemptRef(start.getAttemptId()),
            start.getAmount().movePointRight(2).toBigInteger().toString()));
        assertThat(paid.getOutcome()).isEqualTo(EventOutcome.PROCESSED);
        var version = deliveryService.submit(accepted.artistUserId(), accepted.commissionId(),
            DeliverableSubmission.builder()
                .withNotes("Final render")
                .withUploads(List.of(FileUpload.builder()
                    .withOriginalName("cat.png")
                    .withBytes(new byte[] {1, 2, 3})
                    .build()))
                .build());

        return new Submitted(accepted.customerId(), accepted.artistId(), accepted.commissionId(), accepted.quoteId(),
            version.getDeliverableId());
    }

    private Accepted acceptedQuote(String price) {
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
            .withPrice(new BigDecimal(price))
            .withScopeText("Full-body portrait, one character")
            .withExpectedCompletionTerms("Within 3 weeks of the first payment")
            .build());
        quoteService.accept(customerId, commission.getCommissionId(), quote.getQuoteId());

        return new Accepted(customerId, artistUserId, artist.getArtistId(), commission.getCommissionId(),
            quote.getQuoteId());
    }

    private String startFinal(Submitted approved) {
        var start = paymentService.startQuote(approved.customerId(), approved.commissionId(), approved.quoteId(),
            "203.0.113.7");
        assertThat(start.getInstallment()).isEqualTo(2);

        return attemptRef(start.getAttemptId());
    }

    private List<Map<String, Object>> quoteProceeds(Long quoteId) {
        return jdbc.queryForList("SELECT ProceedsId, Installment, Amount, Status, EligibleAt FROM dbo.ArtistProceeds "
            + "WHERE QuoteId = ? ORDER BY Installment", quoteId);
    }

    private List<Map<String, Object>> quoteReleases(Long quoteId) {
        return jdbc.queryForList("SELECT p.PayoutId, p.ArtistId, p.Amount, p.Status FROM dbo.Payout p "
            + "JOIN dbo.PayoutLine l ON l.PayoutId = p.PayoutId JOIN dbo.ArtistProceeds a ON a.ProceedsId = l.ProceedsId "
            + "WHERE a.QuoteId = ? ORDER BY a.Installment", quoteId);
    }

    private BigDecimal artistBalance(Long quoteId, String account) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(Amount), 0) FROM dbo.LedgerEntry WHERE QuoteId = ? "
            + "AND Account = ?", BigDecimal.class, quoteId, account);
    }

    private String attemptRef(long attemptId) {
        return jdbc.queryForObject("SELECT ProviderAttemptRef FROM dbo.PaymentAttempt WHERE AttemptId = ?",
            String.class, attemptId);
    }

    private String attemptStatus(String ref) {
        return jdbc.queryForObject("SELECT Status FROM dbo.PaymentAttempt WHERE ProviderAttemptRef = ?",
            String.class, ref);
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

    private Map<String, Object> line(List<Map<String, Object>> lines, String account) {
        return lines.stream().filter(row -> account.equals(row.get("Account"))).findFirst().orElseThrow();
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
            "f18b-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Member")).getUserId();
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
            .orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount("f18b-test-" + UUID.randomUUID(),
                "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now())));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private record Accepted(Long customerId, Long artistUserId, Long artistId, Long commissionId, Long quoteId) {
    }

    private record Submitted(Long customerId, Long artistId, Long commissionId, Long quoteId, Long deliverableId) {
    }
}
