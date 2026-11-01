package com.exe101.payment.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.artwork.repository.ArtworkRepository;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.Artwork;
import com.exe101.entity.Listing;
import com.exe101.entity.StaffAccount;
import com.exe101.listing.repository.ListingRepository;
import com.exe101.order.service.OrderService;
import com.exe101.payment.config.VnpayProperties;
import com.exe101.payment.model.EventOutcome;
import com.exe101.payment.model.NotificationResult;
import com.exe101.payment.model.VnpayReply;
import com.exe101.payment.service.PaymentService;
import com.exe101.staff.repository.StaffRepository;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

// Payments must commit for the concurrency and rollback checks to mean anything, and committed Orders can never be
// deleted (immutability triggers). So this runs only against the disposable test database
// (database/test/reset_test_database.sql), selected by TEST_DB_URL, and leaves its rows there. Each test uses a
// new Customer and new Orders. The expiry job is slowed so it runs only when a test calls it.
@SpringBootTest(properties = "app.orders.expiry-interval=PT1H")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class PaymentPersistenceTest {
    private static String printSpecifications = "Fine-art print, A4 (210 x 297 mm), 230 gsm matte paper";
    private static long blockedMillis = 500;
    private static long waitSeconds = 30;
    @Autowired
    private PaymentService service;
    @Autowired
    private OrderService orderService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private ArtworkRepository artworkRepository;
    @Autowired
    private ListingRepository listingRepository;
    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private VnpayProperties vnpay;
    @MockitoSpyBean
    private ProviderEventRepository eventRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    private Long buyerId;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
    }

    @BeforeEach
    void setUp() {
        buyerId = customerService.register(new CustomerRegistration(
            "f11-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Buyer")).getUserId();
    }

    @Test
    void duplicate_success_callbacks_commit_once_ok() {
        // given
        var orderId = pendingOrder("151000", 0, 30);
        var ref = attemptRef(service.start(buyerId, orderId, "203.0.113.7").getAttemptId());
        var success = success(ref, "15100000");

        // when
        var first = service.handleVnpayNotification(success);
        var second = service.handleVnpayNotification(success);

        // then
        assertAll(
            () -> assertThat(first.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(second.isDuplicate()).isTrue(),
            () -> assertThat(second.getOutcome()).isEqualTo(EventOutcome.PROCESSED),
            () -> assertThat(orderStatus(orderId)).isEqualTo("PAID"),
            () -> assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED"),
            () -> assertThat(succeededAttempts(orderId)).isEqualTo(1),
            () -> assertThat(events(ref)).isEqualTo(1)
        );
    }

    @Test
    void concurrent_duplicate_success_callbacks_commit_once_ok() throws Exception {
        // given
        var orderId = pendingOrder("151000", 0, 30);
        var ref = attemptRef(service.start(buyerId, orderId, "203.0.113.7").getAttemptId());
        var success = success(ref, "15100000");
        var handled = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The first copy is handled and held uncommitted while the second copy arrives.
            Future<NotificationResult> first = executor.submit(() -> inTransaction(() -> {
                var result = service.handleVnpayNotification(success);
                handled.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return result;
            }));
            handled.await(waitSeconds, TimeUnit.SECONDS);
            Future<NotificationResult> second = executor.submit(() -> service.handleVnpayNotification(success));
            var secondWaited = isBlocked(second);
            release.countDown();
            var firstResult = first.get(waitSeconds, TimeUnit.SECONDS);
            var secondResult = second.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(secondWaited).isTrue(),
                () -> assertThat(firstResult.getReply()).isEqualTo(VnpayReply.CONFIRMED),
                () -> assertThat(secondResult.isDuplicate()).isTrue(),
                () -> assertThat(orderStatus(orderId)).isEqualTo("PAID"),
                () -> assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED"),
                () -> assertThat(succeededAttempts(orderId)).isEqualTo(1),
                () -> assertThat(events(ref)).isEqualTo(1)
            );
        }
    }

    @Test
    void concurrent_successes_of_two_attempts_commit_once_ok() throws Exception {
        // given
        var orderId = pendingOrder("151000", 0, 30);
        var firstRef = attemptRef(service.start(buyerId, orderId, "203.0.113.7").getAttemptId());
        var secondRef = attemptRef(service.start(buyerId, orderId, "203.0.113.7").getAttemptId());
        var handled = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            Future<NotificationResult> first = executor.submit(() -> inTransaction(() -> {
                var result = service.handleVnpayNotification(success(firstRef, "15100000"));
                handled.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return result;
            }));
            handled.await(waitSeconds, TimeUnit.SECONDS);
            Future<NotificationResult> second = executor.submit(() ->
                service.handleVnpayNotification(success(secondRef, "15100000")));
            var secondWaited = isBlocked(second);
            release.countDown();
            var firstResult = first.get(waitSeconds, TimeUnit.SECONDS);
            var secondResult = second.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(secondWaited).isTrue(),
                () -> assertThat(firstResult.getReply()).isEqualTo(VnpayReply.CONFIRMED),
                () -> assertThat(secondResult.getOutcome()).isEqualTo(EventOutcome.EXCEPTION),
                () -> assertThat(attemptStatus(firstRef)).isEqualTo("SUCCEEDED"),
                () -> assertThat(attemptStatus(secondRef)).isEqualTo("EXCEPTION"),
                () -> assertThat(orderStatus(orderId)).isEqualTo("PAID"),
                () -> assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED"),
                () -> assertThat(succeededAttempts(orderId)).isEqualTo(1)
            );
        }
    }

    @Test
    void success_racing_expiry_job_has_one_outcome_ok() throws Exception {
        // given
        // 1. The job expires an overdue Order and holds it: a success waits, then finds it expired.
        var overdueId = pendingOrder("151000", -40, -1);
        var overdueRef = insertedAttempt(overdueId);
        // 2. A success holds an Order inside its window; once the window closes, the job waits, then skips it.
        var closingId = closingOrder("151000", 3);
        var closingRef = attemptRef(service.start(buyerId, closingId, "203.0.113.7").getAttemptId());
        // Read before the success locks the Order; a read during that lock would wait for it.
        var closingExpiresAt = jdbc.queryForObject("SELECT ExpiresAt FROM dbo.MarketplaceOrder WHERE OrderId = ?",
            OffsetDateTime.class, closingId);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var heldAgain = new CountDownLatch(1);
        var releaseAgain = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            Future<Integer> job = executor.submit(() -> inTransaction(() -> {
                var expired = orderService.expireDue();
                held.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return expired;
            }));
            held.await(waitSeconds, TimeUnit.SECONDS);
            Future<NotificationResult> late = executor.submit(() ->
                service.handleVnpayNotification(success(overdueRef, "15100000")));
            var successWaited = isBlocked(late);
            release.countDown();
            job.get(waitSeconds, TimeUnit.SECONDS);
            var lateResult = late.get(waitSeconds, TimeUnit.SECONDS);

            Future<NotificationResult> inTime = executor.submit(() -> inTransaction(() -> {
                var result = service.handleVnpayNotification(success(closingRef, "15100000"));
                heldAgain.countDown();
                releaseAgain.await(waitSeconds, TimeUnit.SECONDS);
                return result;
            }));
            heldAgain.await(waitSeconds, TimeUnit.SECONDS);
            waitUntilPassed(closingExpiresAt);
            Future<Integer> laterJob = executor.submit(() -> orderService.expireDue());
            var jobWaited = isBlocked(laterJob);
            releaseAgain.countDown();
            var inTimeResult = inTime.get(waitSeconds, TimeUnit.SECONDS);
            laterJob.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(successWaited).isTrue(),
                () -> assertThat(lateResult.getOutcome()).isEqualTo(EventOutcome.EXCEPTION),
                () -> assertThat(orderStatus(overdueId)).isEqualTo("EXPIRED"),
                () -> assertThat(paidAt(overdueId)).isNull(),
                () -> assertThat(paymentStatus(overdueId)).isEqualTo("EXCEPTION"),
                () -> assertThat(attemptStatus(overdueRef)).isEqualTo("EXCEPTION"),
                () -> assertThat(jobWaited).isTrue(),
                () -> assertThat(inTimeResult.getReply()).isEqualTo(VnpayReply.CONFIRMED),
                () -> assertThat(orderStatus(closingId)).isEqualTo("PAID"),
                () -> assertThat(paymentStatus(closingId)).isEqualTo("SUCCEEDED")
            );
        }
    }

    @Test
    void success_after_expiry_job_keeps_order_expired_ok() {
        // given
        var orderId = pendingOrder("151000", -40, -1);
        var ref = insertedAttempt(orderId);
        orderService.expireDue();

        // when
        var result = service.handleVnpayNotification(success(ref, "15100000"));

        // then
        assertAll(
            () -> assertThat(result.getOutcome()).isEqualTo(EventOutcome.EXCEPTION),
            () -> assertThat(orderStatus(orderId)).isEqualTo("EXPIRED"),
            () -> assertThat(paidAt(orderId)).isNull(),
            () -> assertThat(paymentStatus(orderId)).isEqualTo("EXCEPTION"),
            () -> assertThat(attemptStatus(ref)).isEqualTo("EXCEPTION"),
            () -> assertThat(eventOutcome(ref)).isEqualTo("EXCEPTION")
        );
    }

    @Test
    void failed_success_handling_rolls_back_ok() {
        // given
        // The event row is written last: by then the Order, Payment and attempt have been updated.
        var orderId = pendingOrder("151000", 0, 30);
        var ref = attemptRef(service.start(buyerId, orderId, "203.0.113.7").getAttemptId());
        var success = success(ref, "15100000");
        doThrow(new IllegalStateException("event insert failed")).when(eventRepository).saveAndFlush(any());

        // when
        assertThatThrownBy(() -> service.handleVnpayNotification(success)).isInstanceOf(IllegalStateException.class);
        var orderAfterFailure = orderStatus(orderId);
        var paymentAfterFailure = paymentStatus(orderId);
        var attemptAfterFailure = attemptStatus(ref);
        var eventsAfterFailure = events(ref);
        reset(eventRepository);
        var retried = service.handleVnpayNotification(success);

        // then
        assertAll(
            () -> assertThat(orderAfterFailure).isEqualTo("PENDING_PAYMENT"),
            () -> assertThat(paymentAfterFailure).isEqualTo("PENDING"),
            () -> assertThat(attemptAfterFailure).isEqualTo("INITIATED"),
            () -> assertThat(eventsAfterFailure).isZero(),
            () -> assertThat(retried.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(orderStatus(orderId)).isEqualTo("PAID"),
            () -> assertThat(events(ref)).isEqualTo(1)
        );
    }

    @Test
    void paid_amount_equals_order_total_exactly_ok() {
        // given
        // 270.005 items + 30.000 shipping + 27.001 tax = 327.006 VND, sent to VNPay as 32.700.600.
        var orderId = jdbc.queryForObject("INSERT INTO dbo.MarketplaceOrder (CustomerId, Status, CreatedAt,"
                + " ExpiresAt, ItemsSubtotal, ShippingAmount, TaxAmount) OUTPUT INSERTED.OrderId"
                + " VALUES (?, 'PENDING_PAYMENT', ?, ?, 270005, 30000, 27001)",
            Long.class, buyerId, now(), now().plusMinutes(30));
        jdbc.update("INSERT INTO dbo.Payment (OrderId, Amount) VALUES (?, 327006)", orderId);
        printItem(orderId, "270005", "27001");
        var shortRef = attemptRef(service.start(buyerId, orderId, "203.0.113.7").getAttemptId());
        var start = service.start(buyerId, orderId, "203.0.113.7");
        var exactRef = attemptRef(start.getAttemptId());

        // when
        var oneShort = service.handleVnpayNotification(success(shortRef, "32700500"));
        var exact = service.handleVnpayNotification(success(exactRef, "32700600"));

        // then
        var amounts = jdbc.queryForMap("SELECT o.TotalAmount, o.PaidAt, p.Amount, p.SucceededAt"
            + " FROM dbo.MarketplaceOrder o JOIN dbo.Payment p ON p.OrderId = o.OrderId WHERE o.OrderId = ?", orderId);
        assertAll(
            () -> assertThat(start.getPaymentUrl()).contains("vnp_Amount=32700600"),
            () -> assertThat(oneShort.getReply()).isEqualTo(VnpayReply.INVALID_AMOUNT),
            () -> assertThat(attemptStatus(shortRef)).isEqualTo("EXCEPTION"),
            () -> assertThat(exact.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(amounts.get("TotalAmount")).isEqualTo(new BigDecimal("327006.0000")),
            () -> assertThat(amounts.get("Amount")).isEqualTo(amounts.get("TotalAmount")),
            () -> assertThat(amounts.get("PaidAt")).isEqualTo(amounts.get("SucceededAt")),
            () -> assertThat(orderStatus(orderId)).isEqualTo("PAID"),
            () -> assertThat(succeededAttempts(orderId)).isEqualTo(1)
        );
    }

    private long pendingOrder(String amount, int createdMinutes, int expiresMinutes) {
        var orderId = jdbc.queryForObject("INSERT INTO dbo.MarketplaceOrder (CustomerId, Status, CreatedAt,"
                + " ExpiresAt, ItemsSubtotal) OUTPUT INSERTED.OrderId VALUES (?, 'PENDING_PAYMENT', ?, ?, ?)",
            Long.class, buyerId, now().plusMinutes(createdMinutes),
            now().plusMinutes(expiresMinutes).plusSeconds(1), new BigDecimal(amount));
        jdbc.update("INSERT INTO dbo.Payment (OrderId, Amount) VALUES (?, ?)", orderId, new BigDecimal(amount));
        printItem(orderId, amount, "0");

        return orderId;
    }

    // ExpiresAt is immutable, so an Order whose window closes during the test is created that way.
    private long closingOrder(String amount, int expiresSeconds) {
        var orderId = jdbc.queryForObject("INSERT INTO dbo.MarketplaceOrder (CustomerId, Status, CreatedAt,"
                + " ExpiresAt, ItemsSubtotal) OUTPUT INSERTED.OrderId VALUES (?, 'PENDING_PAYMENT', ?, ?, ?)",
            Long.class, buyerId, now(), now().plusSeconds(expiresSeconds), new BigDecimal(amount));
        jdbc.update("INSERT INTO dbo.Payment (OrderId, Amount) VALUES (?, ?)", orderId, new BigDecimal(amount));
        printItem(orderId, amount, "0");

        return orderId;
    }

    // F10: an Order has at least one item, and its subtotal and tax come from its items. A paid Order without them
    // owes its money to nobody, and the ledger refuses to book it. One print of another Customer's Artist Profile:
    // manufacturing 1 000, markup the rest of the subtotal.
    private void printItem(long orderId, String subtotal, String tax) {
        var price = new BigDecimal(subtotal);
        var cost = new BigDecimal("1000");
        var markup = price.subtract(cost);
        var sellerId = customerService.register(new CustomerRegistration(
            "f11-seller-" + UUID.randomUUID() + "@example.invalid", "long-password", "Seller")).getUserId();
        var artist = artistRepository.saveAndFlush(new ArtistProfile(sellerId, "Painter", "ACTIVE", false,
            admin().getStaffId(), now(), null));
        var artwork = artworkRepository.saveAndFlush(new Artwork(artist.getArtistId(), "Sunset", null, "2D",
            "PUBLISHED", now(), now(), null));
        var listing = listingRepository.saveAndFlush(new Listing(artwork.getArtworkId(), "PHYSICAL",
            "Sunset A4 print", null, "PUBLISHED", "VND", price, cost, markup, printSpecifications, false, now(), now(),
            null));
        jdbc.update("INSERT INTO dbo.OrderItem (OrderId, ListingId, ListingType, ArtworkId, ArtistId, ArtworkTitle,"
                + " ListingName, ArtistName, Specifications, Quantity, UnitPrice, ManufacturingCostPerUnit,"
                + " MarkupPerUnit, FeeRate, FeeAmount, ArtistProceeds, TaxAmount) VALUES (?, ?, 'PHYSICAL', ?, ?,"
                + " N'Sunset', N'Sunset A4 print', N'Painter', ?, 1, ?, ?, ?, 0, 0, ?, ?)",
            orderId, listing.getListingId(), artwork.getArtworkId(), artist.getArtistId(), printSpecifications,
            price, cost, markup, markup, new BigDecimal(tax));
    }

    private StaffAccount admin() {
        return staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive())
            .findFirst()
            .orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount("f11-test-" + UUID.randomUUID(),
                "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now())));
    }

    // For an Order already past its window, where the API would refuse to start an attempt.
    private String insertedAttempt(long orderId) {
        var ref = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO dbo.PaymentAttempt (PaymentId, ProviderName, ProviderAttemptRef, CreatedAt)"
            + " SELECT PaymentId, 'VNPAY', ?, ? FROM dbo.Payment WHERE OrderId = ?", ref, now().minusMinutes(5),
            orderId);

        return ref;
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
        fields.put("vnp_PayDate", "20261101103000");
        fields.put("vnp_ResponseCode", "00");
        fields.put("vnp_TransactionNo", String.valueOf(Math.abs(ref.hashCode())));
        fields.put("vnp_TransactionStatus", "00");
        fields.put("vnp_TxnRef", ref);
        var query = fields.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.US_ASCII))
            .collect(Collectors.joining("&"));
        var parameters = new HashMap<>(fields);
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

    private void waitUntilPassed(OffsetDateTime expiresAt) throws InterruptedException {
        var millis = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), expiresAt).toMillis() + 1000;
        Thread.sleep(Math.max(millis, 0));
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.MarketplaceOrder WHERE OrderId = ?", String.class,
            orderId);
    }

    private Object paidAt(long orderId) {
        return jdbc.queryForObject("SELECT PaidAt FROM dbo.MarketplaceOrder WHERE OrderId = ?", Object.class,
            orderId);
    }

    private String paymentStatus(long orderId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.Payment WHERE OrderId = ?", String.class, orderId);
    }

    private String attemptStatus(String ref) {
        return jdbc.queryForObject("SELECT Status FROM dbo.PaymentAttempt WHERE ProviderAttemptRef = ?",
            String.class, ref);
    }

    private int succeededAttempts(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.PaymentAttempt a JOIN dbo.Payment p"
            + " ON p.PaymentId = a.PaymentId WHERE p.OrderId = ? AND a.Status = 'SUCCEEDED'", Integer.class, orderId);
    }

    private int events(String ref) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.ProviderEvent WHERE ExternalEventId LIKE ?",
            Integer.class, ref + ":%");
    }

    private String eventOutcome(String ref) {
        return jdbc.queryForObject("SELECT Outcome FROM dbo.ProviderEvent WHERE ExternalEventId LIKE ?",
            String.class, ref + ":%");
    }

    // Runs the work in a transaction that commits when it returns, so the caller can hold its locks.
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

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
