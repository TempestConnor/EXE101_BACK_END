package com.exe101.commission.repository;

import com.exe101.artist.model.TermsPublication;
import com.exe101.artist.repository.ArtistRepository;
import com.exe101.artist.service.ArtistTermsService;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionRequest;
import com.exe101.commission.model.QuoteTerms;
import com.exe101.commission.service.CommissionQuoteService;
import com.exe101.commission.service.CommissionService;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.StaffAccount;
import com.exe101.ledger.service.LedgerService;
import com.exe101.payment.config.VnpayProperties;
import com.exe101.payment.model.EventOutcome;
import com.exe101.payment.model.NotificationResult;
import com.exe101.payment.model.VnpayReply;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

// Quote payments commit, and Quotes can never be deleted (TR_Quote_Terms_Immutable), so this runs only against the
// disposable test database (database/test/reset_test_database.sql), selected by TEST_DB_URL, and leaves its rows
// there. Every committed payment writes its ledger receipt, so the database stays reconcilable. At a 10% tax rate a
// price of 100 001 has a tax of 10 000; the first payment is 50 001 + 5 000 = 55 001. The scheduled jobs are slowed
// down so they do not run during a test.
@SpringBootTest(properties = {"app.orders.expiry-interval=PT1H", "app.fulfillment.grant-interval=PT1H",
    "app.checkout.tax-rate=0.10"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class QuotePaymentPersistenceTest {
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
    void quote_first_payment_success_persists_ok() {
        // given
        var accepted = acceptedQuote();
        var ref = attemptRef(paymentService.startQuote(accepted.customerId(), accepted.commissionId(),
            accepted.quoteId(), "203.0.113.7").getAttemptId());

        // when
        var result = paymentService.handleVnpayNotification(success(ref, firstTimes100));

        // then
        var payment = jdbc.queryForMap("SELECT PaymentId, OrderId, Installment, Amount, Status, SucceededAt "
            + "FROM dbo.Payment WHERE QuoteId = ?", accepted.quoteId());
        var paymentId = ((Number) payment.get("PaymentId")).longValue();
        var proceeds = jdbc.queryForList("SELECT ArtistId, OrderItemId, Installment, Amount, Status "
            + "FROM dbo.ArtistProceeds WHERE QuoteId = ?", accepted.quoteId());
        var quote = jdbc.queryForMap("SELECT Status, PaidAt FROM dbo.CommissionQuote WHERE QuoteId = ?",
            accepted.quoteId());
        var lines = jdbc.queryForList("SELECT e.Account, e.ArtistId, e.OrderItemId, e.QuoteId, e.Amount "
            + "FROM dbo.LedgerEntry e JOIN dbo.LedgerTransaction t ON t.TransactionId = e.TransactionId "
            + "WHERE t.Type = 'PAYMENT_RECEIVED' AND t.SourceType = 'PAYMENT' AND t.SourceId = ?", paymentId);
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(result.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(result.getOutcome()).isEqualTo(EventOutcome.PROCESSED),
            () -> assertThat(payment.get("OrderId")).isNull(),
            () -> assertThat(((Number) payment.get("Installment")).intValue()).isEqualTo(1),
            () -> assertThat((BigDecimal) payment.get("Amount")).isEqualByComparingTo("55001"),
            () -> assertThat(payment.get("Status")).isEqualTo("SUCCEEDED"),
            () -> assertThat(payment.get("SucceededAt")).isNotNull(),
            () -> assertThat(commissionStatus(accepted.commissionId())).isEqualTo("IN_PROGRESS"),
            () -> assertThat(quote.get("Status")).isEqualTo("ACCEPTED"),
            () -> assertThat(quote.get("PaidAt")).isNull(),
            () -> assertThat(events(accepted.commissionId()))
                .containsExactly("REQUESTED", "QUOTE_OFFERED", "QUOTE_ACCEPTED", "PAYMENT_CONFIRMED"),
            () -> assertThat(proceeds).singleElement().satisfies(row -> assertAll(
                () -> assertThat(((Number) row.get("ArtistId")).longValue()).isEqualTo(accepted.artistId()),
                () -> assertThat(row.get("OrderItemId")).isNull(),
                () -> assertThat(((Number) row.get("Installment")).intValue()).isEqualTo(1),
                () -> assertThat((BigDecimal) row.get("Amount")).isEqualByComparingTo("50001"),
                () -> assertThat(row.get("Status")).isEqualTo("HELD")
            )),
            () -> assertThat(lines).hasSize(3),
            () -> assertThat(sum(lines)).isEqualByComparingTo("0"),
            () -> assertThat(line(lines, "CASH").get("QuoteId")).isNull(),
            () -> assertThat((BigDecimal) line(lines, "CASH").get("Amount")).isEqualByComparingTo("55001"),
            () -> assertThat((BigDecimal) line(lines, "ARTIST_HELD").get("Amount")).isEqualByComparingTo("-50001"),
            () -> assertThat(((Number) line(lines, "ARTIST_HELD").get("QuoteId")).longValue())
                .isEqualTo(accepted.quoteId()),
            () -> assertThat(((Number) line(lines, "ARTIST_HELD").get("ArtistId")).longValue())
                .isEqualTo(accepted.artistId()),
            () -> assertThat(line(lines, "ARTIST_HELD").get("OrderItemId")).isNull(),
            () -> assertThat((BigDecimal) line(lines, "TAX_PAYABLE").get("Amount")).isEqualByComparingTo("-5000"),
            () -> assertThat(reconciliation.getMismatches()).isEmpty(),
            () -> assertThat(reconciliation.isClean()).isTrue()
        );
    }

    @Test
    void duplicate_quote_success_applies_once_ok() {
        // given
        var accepted = acceptedQuote();
        var ref = attemptRef(paymentService.startQuote(accepted.customerId(), accepted.commissionId(),
            accepted.quoteId(), "203.0.113.7").getAttemptId());
        var success = success(ref, firstTimes100);

        // when
        var first = paymentService.handleVnpayNotification(success);
        var second = paymentService.handleVnpayNotification(success);

        // then
        assertAll(
            () -> assertThat(first.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(second.isDuplicate()).isTrue(),
            () -> assertThat(second.getOutcome()).isEqualTo(EventOutcome.PROCESSED),
            () -> assertThat(commissionStatus(accepted.commissionId())).isEqualTo("IN_PROGRESS"),
            () -> assertThat(count("SELECT COUNT(*) FROM dbo.PaymentAttempt a JOIN dbo.Payment p "
                + "ON p.PaymentId = a.PaymentId WHERE p.QuoteId = ? AND a.Status = 'SUCCEEDED'", accepted.quoteId()))
                .isEqualTo(1),
            () -> assertThat(count("SELECT COUNT(*) FROM dbo.ArtistProceeds WHERE QuoteId = ?", accepted.quoteId()))
                .isEqualTo(1),
            () -> assertThat(count("SELECT COUNT(*) FROM dbo.LedgerTransaction t JOIN dbo.Payment p "
                + "ON p.PaymentId = t.SourceId WHERE t.SourceType = 'PAYMENT' AND p.QuoteId = ?", accepted.quoteId()))
                .isEqualTo(1),
            () -> assertThat(events(accepted.commissionId())).filteredOn("PAYMENT_CONFIRMED"::equals).hasSize(1),
            () -> assertThat(ledgerService.reconcile().isClean()).isTrue()
        );
    }

    @Test
    void concurrent_quote_successes_one_wins_ok() throws Exception {
        // given
        // Two attempts for the same first payment both succeed at VNPay.
        var accepted = acceptedQuote();
        var firstRef = attemptRef(paymentService.startQuote(accepted.customerId(), accepted.commissionId(),
            accepted.quoteId(), "203.0.113.7").getAttemptId());
        var secondRef = attemptRef(paymentService.startQuote(accepted.customerId(), accepted.commissionId(),
            accepted.quoteId(), "203.0.113.7").getAttemptId());
        var handled = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The first success is handled and held uncommitted while the second arrives.
            Future<NotificationResult> first = executor.submit(() -> inTransaction(() -> {
                var result = paymentService.handleVnpayNotification(success(firstRef, firstTimes100));
                handled.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return result;
            }));
            assertThat(handled.await(waitSeconds, TimeUnit.SECONDS)).isTrue();
            Future<NotificationResult> second = executor.submit(() ->
                paymentService.handleVnpayNotification(success(secondRef, firstTimes100)));
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
                () -> assertThat(count("SELECT COUNT(*) FROM dbo.Payment WHERE QuoteId = ?", accepted.quoteId()))
                    .isEqualTo(1),
                () -> assertThat(jdbc.queryForObject("SELECT Status FROM dbo.Payment WHERE QuoteId = ?",
                    String.class, accepted.quoteId())).isEqualTo("SUCCEEDED"),
                () -> assertThat(count("SELECT COUNT(*) FROM dbo.ArtistProceeds WHERE QuoteId = ?",
                    accepted.quoteId())).isEqualTo(1),
                () -> assertThat(events(accepted.commissionId())).filteredOn("PAYMENT_CONFIRMED"::equals)
                    .hasSize(1),
                () -> assertThat(count("SELECT COUNT(*) FROM dbo.LedgerTransaction t JOIN dbo.ProviderEvent e "
                    + "ON e.ProviderEventId = t.SourceId JOIN dbo.PaymentAttempt a ON a.AttemptId = e.AttemptId "
                    + "WHERE t.Type = 'PAYMENT_UNAPPLIED' AND a.ProviderAttemptRef = ?", secondRef)).isEqualTo(1),
                () -> assertThat(reconciliation.getMismatches()).isEmpty()
            );
        }
    }

    @Test
    void quote_payment_installment_constraints_ko() {
        // given
        var accepted = acceptedQuote();
        var insert = "INSERT INTO dbo.Payment (OrderId, QuoteId, Installment, Amount, CurrencyCode, Status, CreatedAt) "
            + "VALUES (NULL, ?, ?, 55001, 'VND', 'PENDING', SYSDATETIMEOFFSET())";

        // when / then
        // Nothing here commits: each check runs in a transaction that is rolled back.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update(insert, accepted.quoteId(), 1);
            assertAll(
                () -> assertThatThrownBy(() -> jdbc.update(insert, accepted.quoteId(), 1))
                    .isInstanceOf(DataAccessException.class),
                () -> assertThatThrownBy(() -> jdbc.update(insert, accepted.quoteId(), null))
                    .isInstanceOf(DataAccessException.class),
                () -> assertThatThrownBy(() -> jdbc.update(insert, accepted.quoteId(), 3))
                    .isInstanceOf(DataAccessException.class),
                () -> assertThat(jdbc.update(insert, accepted.quoteId(), 2)).isEqualTo(1)
            );
            status.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM dbo.Payment WHERE QuoteId = ?", accepted.quoteId())).isZero();
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

        return new Accepted(customerId, artist.getArtistId(), commission.getCommissionId(), quote.getQuoteId());
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

    private BigDecimal sum(List<Map<String, Object>> lines) {
        return lines.stream().map(row -> (BigDecimal) row.get("Amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
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
            "f17b-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Member")).getUserId();
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
            .orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount("f17b-test-" + UUID.randomUUID(),
                "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now())));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private record Accepted(Long customerId, Long artistId, Long commissionId, Long quoteId) {
    }
}
