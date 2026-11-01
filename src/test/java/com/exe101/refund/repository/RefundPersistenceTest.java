package com.exe101.refund.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.artwork.repository.ArtworkRepository;
import com.exe101.audit.repository.AuditRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.Artwork;
import com.exe101.entity.Listing;
import com.exe101.entity.ListingAsset;
import com.exe101.entity.StaffAccount;
import com.exe101.file.config.StorageProperties;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.service.FileService;
import com.exe101.fulfillment.config.ReturnWindowJob;
import com.exe101.fulfillment.model.FulfillmentChange;
import com.exe101.fulfillment.model.FulfillmentStatus;
import com.exe101.fulfillment.service.FulfillmentService;
import com.exe101.ledger.model.Mismatch;
import com.exe101.ledger.service.LedgerService;
import com.exe101.listing.repository.ListingAssetRepository;
import com.exe101.listing.repository.ListingRepository;
import com.exe101.payment.config.VnpayProperties;
import com.exe101.payment.service.PaymentService;
import com.exe101.refund.model.RefundDecision;
import com.exe101.refund.model.RefundVerdict;
import com.exe101.refund.model.TransferOutcome;
import com.exe101.refund.model.TransferStatus;
import com.exe101.refund.service.RefundService;
import com.exe101.staff.repository.StaffRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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

// Refunds follow a committed payment, and committed Orders can never be deleted (immutability triggers). So this runs
// only against the disposable test database (database/test/reset_test_database.sql), selected by TEST_DB_URL, and
// leaves its rows there. Every fixture is paid through VNPay so its ledger receipt exists, and every refund it makes
// is posted, so the test database stays reconcilable. Each print is 110 000 (80 000 cost + 30 000 markup) with
// 11 000 tax; each Order's shipping is 20 000. The scheduled jobs are slowed down so only the tests run them.
@SpringBootTest(properties = {"app.orders.expiry-interval=PT1H", "app.fulfillment.grant-interval=PT1H"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class RefundPersistenceTest {
    private static String a4Specifications = "Fine-art print, A4 (210 x 297 mm), 230 gsm matte paper";
    private static byte[] fileBytes = "purchased-file".getBytes(StandardCharsets.UTF_8);
    private static BigDecimal unitPrice = new BigDecimal("110000");
    private static BigDecimal tax = new BigDecimal("11000");
    private static BigDecimal shipping = new BigDecimal("20000");
    private static long blockedMillis = 500;
    private static long waitSeconds = 30;
    @Autowired
    private RefundService refundService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private FulfillmentService fulfillmentService;
    @Autowired
    private ReturnWindowJob returnWindowJob;
    @Autowired
    private FileService fileService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private ListingRepository listingRepository;
    @Autowired
    private ListingAssetRepository listingAssetRepository;
    @Autowired
    private ArtworkRepository artworkRepository;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private StaffRepository staffRepository;
    @MockitoSpyBean
    private AuditRepository auditRepository;
    @Autowired
    private VnpayProperties vnpay;
    @Autowired
    private StorageProperties storage;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    private Long buyerId;
    private List<String> storageKeys;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
    }

    @BeforeEach
    void setUp() {
        buyerId = registerUser();
        storageKeys = new ArrayList<>();
    }

    @AfterEach
    void tearDown() throws IOException {
        for (var storageKey : storageKeys) {
            Files.deleteIfExists(Path.of(storage.root()).resolve(storageKey));
        }
    }

    @Test
    void two_physical_refunds_shipping_refunded_once_ok() {
        // given
        var orderId = paidOrder(1, 1);
        var items = items(orderId);
        var first = requested(orderId, items.getFirst());
        var second = requested(orderId, items.get(1));

        // when
        var firstRefund = refundService.decide(adminId(), first, approve());
        var shippingAfterFirst = shippingRefunds(orderId);
        var secondRefund = refundService.decide(adminId(), second, approve());

        // then
        assertAll(
            () -> assertThat(shippingAfterFirst).isEmpty(),
            () -> assertThat(firstRefund.getShippingAmount()).isNull(),
            () -> assertThat(transferAmount(first)).isEqualByComparingTo("121000"),
            () -> assertThat(shippingRefunds(orderId)).containsExactly(second),
            () -> assertThat(shippingAmount(orderId)).isEqualByComparingTo("20000"),
            () -> assertThat(secondRefund.getShippingAmount()).isEqualByComparingTo("20000"),
            () -> assertThat(transferAmount(second)).isEqualByComparingTo("141000"),
            () -> assertThat(fulfillmentStatus(items.getFirst())).isEqualTo("CANCELLED"),
            () -> assertThat(fulfillmentStatus(items.get(1))).isEqualTo("CANCELLED"),
            () -> assertThat(proceedsStatus(items.getFirst())).isEqualTo("VOID"),
            () -> assertThat(proceedsStatus(items.get(1))).isEqualTo("VOID")
        );
    }

    @Test
    void concurrent_approvals_create_one_shipping_refund_ok() throws Exception {
        // given
        var orderId = paidOrder(1, 1);
        var items = items(orderId);
        var first = requested(orderId, items.getFirst());
        var second = requested(orderId, items.get(1));
        var adminId = adminId();
        var approved = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The first approval is held uncommitted while the second arrives for the other item of the Order.
            Future<Object> held = executor.submit(() -> inTransaction(() -> {
                var refund = refundService.decide(adminId, first, approve());
                approved.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return refund;
            }));
            approved.await(waitSeconds, TimeUnit.SECONDS);
            Future<Object> concurrent = executor.submit(() -> refundService.decide(adminId, second, approve()));
            var concurrentWaited = isBlocked(concurrent);
            release.countDown();
            held.get(waitSeconds, TimeUnit.SECONDS);
            concurrent.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(concurrentWaited).isTrue(),
                () -> assertThat(shippingRefunds(orderId)).containsExactly(second),
                () -> assertThat(transferAmount(first).add(transferAmount(second))).isEqualByComparingTo("262000")
            );
        }
    }

    @Test
    void approve_refund_after_window_closed_ko() {
        // given
        // The window closed, the files were granted and the proceeds released; a request then reaches the Admin.
        var orderId = paidOrder(1);
        var itemId = items(orderId).getFirst();
        var deliveredAt = deliver(itemId, 49);
        returnWindowJob.grantClosedWindows();
        var requestId = insertedRequest(itemId, deliveredAt.plusHours(1));
        var proceedsBefore = proceedsStatus(itemId);

        // when / then
        assertThatThrownBy(() -> refundService.decide(adminId(), requestId, approve()))
            .isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getError()).isEqualTo(AppErrorMessage.REFUND_NOT_ALLOWED));
        assertAll(
            () -> assertThat(proceedsBefore).isEqualTo("RESERVED"),
            () -> assertThat(entitlements(itemId)).isEqualTo(1),
            () -> assertThat(requestStatus(requestId)).isEqualTo("REQUESTED"),
            () -> assertThat(transfers(requestId)).isZero(),
            () -> assertThat(proceedsStatus(itemId)).isEqualTo("RESERVED"),
            () -> assertThat(fulfillmentStatus(itemId)).isEqualTo("DELIVERED")
        );
    }

    @Test
    void window_close_and_approval_race_one_wins_ok() throws Exception {
        // given
        var orderId = paidOrder(1);
        var itemId = items(orderId).getFirst();
        var requestId = returnRequestedInWindow(orderId, itemId);
        var adminId = adminId();
        var approved = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The approval is held uncommitted while the window close arrives for the same item.
            Future<Object> held = executor.submit(() -> inTransaction(() -> {
                var refund = refundService.decide(adminId, requestId, approve());
                approved.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return refund;
            }));
            approved.await(waitSeconds, TimeUnit.SECONDS);
            Future<Boolean> windowClose = executor.submit(() -> fulfillmentService.grantIfWindowClosed(itemId));
            var windowCloseWaited = isBlocked(windowClose);
            release.countDown();
            held.get(waitSeconds, TimeUnit.SECONDS);
            var granted = windowClose.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(windowCloseWaited).isTrue(),
                () -> assertThat(granted).isFalse(),
                () -> assertThat(requestStatus(requestId)).isEqualTo("APPROVED"),
                () -> assertThat(proceedsStatus(itemId)).isEqualTo("VOID"),
                () -> assertThat(releaseLines(itemId)).isZero(),
                () -> assertThat(entitlements(itemId)).isZero(),
                () -> assertThat(transfers(requestId)).isEqualTo(1)
            );
        }
    }

    @Test
    void open_request_holds_window_release_ok() {
        // given
        var orderId = paidOrder(1);
        var itemId = items(orderId).getFirst();
        returnRequestedInWindow(orderId, itemId);

        // when
        returnWindowJob.grantClosedWindows();

        // then
        assertAll(
            () -> assertThat(entitlements(itemId)).isZero(),
            () -> assertThat(proceedsStatus(itemId)).isEqualTo("HELD"),
            () -> assertThat(releaseLines(itemId)).isZero()
        );
    }

    @Test
    void rejected_request_lets_window_release_ok() {
        // given
        var orderId = paidOrder(1);
        var itemId = items(orderId).getFirst();
        var requestId = returnRequestedInWindow(orderId, itemId);
        refundService.decide(adminId(), requestId, RefundDecision.builder()
            .withVerdict(RefundVerdict.REJECT)
            .withReason("The print matches the listing")
            .build());

        // when
        returnWindowJob.grantClosedWindows();

        // then
        assertAll(
            () -> assertThat(requestStatus(requestId)).isEqualTo("REJECTED"),
            () -> assertThat(entitlements(itemId)).isEqualTo(1),
            () -> assertThat(proceedsStatus(itemId)).isEqualTo("RESERVED"),
            () -> assertThat(releaseLines(itemId)).isEqualTo(1),
            () -> assertThat(audits(requestId)).containsExactly("REFUND_REJECTED")
        );
    }

    @Test
    void duplicate_open_request_rejected_by_database_ok() {
        // given
        var orderId = paidOrder(1);
        var itemId = items(orderId).getFirst();
        requested(orderId, itemId);

        // when / then
        assertThatThrownBy(() -> insertedRequest(itemId, now()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.RefundRequest WHERE OrderItemId = ?", Integer.class,
            itemId)).isEqualTo(1);
    }

    @Test
    void refund_approval_ledger_balanced_and_reconciled_ok() {
        // given
        // Two copies of one print: 220 000 + 22 000 tax + 20 000 shipping = 262 000.
        var orderId = paidOrder(2);
        var itemId = items(orderId).getFirst();
        var requestId = requested(orderId, itemId);

        // when
        refundService.decide(adminId(), requestId, approve());
        var reconciliation = ledgerService.reconcile();

        // then
        var approval = transactions("REFUND_APPROVED", "REFUND_REQUEST", requestId);
        var lines = accountLines(approval.getFirst());
        var ownRecords = List.of(requestId, transferId(requestId), itemId, proceedsId(itemId), paymentIdOf(orderId));
        assertAll(
            () -> assertThat(approval).hasSize(1),
            () -> assertThat(lines.get("ARTIST_HELD")).isEqualByComparingTo("60000"),
            () -> assertThat(lines.get("MANUFACTURER_PAYABLE")).isEqualByComparingTo("160000"),
            () -> assertThat(lines.get("TAX_PAYABLE")).isEqualByComparingTo("22000"),
            () -> assertThat(lines.get("SHIPPING")).isEqualByComparingTo("20000"),
            () -> assertThat(lines.get("REFUND_PAYABLE")).isEqualByComparingTo("-262000"),
            () -> assertThat(lines.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0"),
            () -> assertThat(transferAmount(requestId)).isEqualByComparingTo("262000"),
            () -> assertThat(artistBalance(itemId)).isEqualByComparingTo("0"),
            () -> assertThat(reconciliation.getMismatches()).extracting(Mismatch::getRecordId)
                .doesNotContainAnyElementsOf(ownRecords),
            () -> assertThat(audits(requestId)).containsExactly("REFUND_APPROVED")
        );
    }

    @Test
    void refund_paid_moves_cash_and_reconciles_ok() {
        // given
        var orderId = paidOrder(2);
        var itemId = items(orderId).getFirst();
        var requestId = requested(orderId, itemId);
        refundService.decide(adminId(), requestId, approve());
        var transferId = transferId(requestId);

        // when
        var refund = refundService.recordTransferOutcome(adminId(), transferId, TransferOutcome.builder()
            .withStatus(TransferStatus.SUCCEEDED)
            .withTransferReference("test-" + UUID.randomUUID())
            .withReason("Refunded through the VNPay portal")
            .build());
        var reconciliation = ledgerService.reconcile();

        // then
        var paid = transactions("REFUND_PAID", "REFUND_TRANSFER", transferId);
        var lines = accountLines(paid.getFirst());
        var receipt = transactions("PAYMENT_RECEIVED", "PAYMENT", paymentIdOf(orderId));
        var ownRecords = List.of(requestId, transferId, itemId, proceedsId(itemId), paymentIdOf(orderId));
        assertAll(
            () -> assertThat(refund.getStatus().name()).isEqualTo("REFUNDED"),
            () -> assertThat(requestStatus(requestId)).isEqualTo("REFUNDED"),
            () -> assertThat(paid).hasSize(1),
            () -> assertThat(lines.get("REFUND_PAYABLE")).isEqualByComparingTo("262000"),
            () -> assertThat(lines.get("CASH")).isEqualByComparingTo("-262000"),
            () -> assertThat(accountLines(receipt.getFirst()).get("CASH").add(lines.get("CASH")))
                .isEqualByComparingTo("0"),
            () -> assertThat(reconciliation.getMismatches()).extracting(Mismatch::getRecordId)
                .doesNotContainAnyElementsOf(ownRecords)
        );
    }

    @Test
    void approval_rolls_back_when_audit_fails_ko() {
        // given
        var orderId = paidOrder(1);
        var itemId = items(orderId).getFirst();
        var requestId = requested(orderId, itemId);
        var eventsBefore = events(itemId);
        var adminId = adminId();
        doThrow(new IllegalStateException("audit insert failed")).when(auditRepository).saveAndFlush(any());

        // when
        assertThatThrownBy(() -> refundService.decide(adminId, requestId, approve()))
            .isInstanceOf(IllegalStateException.class);
        var requestAfterFailure = requestStatus(requestId);
        var fulfillmentAfterFailure = fulfillmentStatus(itemId);
        var eventsAfterFailure = events(itemId);
        var proceedsAfterFailure = proceedsStatus(itemId);
        var transfersAfterFailure = transfers(requestId);
        var shippingAfterFailure = shippingRefunds(orderId);
        var ledgerAfterFailure = transactions("REFUND_APPROVED", "REFUND_REQUEST", requestId);
        reset(auditRepository);
        var retried = refundService.decide(adminId, requestId, approve());

        // then
        assertAll(
            () -> assertThat(requestAfterFailure).isEqualTo("REQUESTED"),
            () -> assertThat(fulfillmentAfterFailure).isEqualTo("PENDING"),
            () -> assertThat(eventsAfterFailure).isEqualTo(eventsBefore),
            () -> assertThat(proceedsAfterFailure).isEqualTo("HELD"),
            () -> assertThat(transfersAfterFailure).isZero(),
            () -> assertThat(shippingAfterFailure).isEmpty(),
            () -> assertThat(ledgerAfterFailure).isEmpty(),
            () -> assertThat(retried.getStatus().name()).isEqualTo("APPROVED"),
            () -> assertThat(transactions("REFUND_APPROVED", "REFUND_REQUEST", requestId)).hasSize(1)
        );
    }

    private Long registerUser() {
        return customerService.register(new CustomerRegistration(
            "f15-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Buyer")).getUserId();
    }

    private RefundDecision approve() {
        return RefundDecision.builder().withVerdict(RefundVerdict.APPROVE).withReason("Damaged").build();
    }

    private long requested(long orderId, long itemId) {
        return refundService.request(buyerId, orderId, itemId, "Print arrived torn").getRefundRequestId();
    }

    // Delivered and asked about within the window, then both moved back in time so the window has closed since.
    private long returnRequestedInWindow(long orderId, long itemId) {
        deliver(itemId, 0);
        var requestId = requested(orderId, itemId);
        var deliveredAt = now().minusHours(49);
        jdbc.update("UPDATE dbo.PhysicalFulfillment SET DeliveredAt = ?, UpdatedAt = ? WHERE OrderItemId = ?",
            deliveredAt, deliveredAt, itemId);
        jdbc.update("UPDATE dbo.RefundRequest SET RequestedAt = ? WHERE RefundRequestId = ?", deliveredAt.plusHours(1),
            requestId);

        return requestId;
    }

    // A request written directly, bypassing the service's checks.
    private long insertedRequest(long itemId, OffsetDateTime requestedAt) {
        return jdbc.queryForObject("INSERT INTO dbo.RefundRequest (OrderItemId, RequestedByUserId, Reason, Status,"
                + " RequestedAt) OUTPUT INSERTED.RefundRequestId VALUES (?, ?, N'Late', 'REQUESTED', ?)", Long.class,
            itemId, buyerId, requestedAt);
    }

    // Delivered by the Admin, then moved back in time.
    private OffsetDateTime deliver(long itemId, int hoursAgo) {
        var adminId = adminId();
        var steps = List.of(FulfillmentStatus.IN_PRODUCTION, FulfillmentStatus.READY_TO_SHIP,
            FulfillmentStatus.SHIPPED, FulfillmentStatus.DELIVERED);

        for (var step : steps) {
            fulfillmentService.advance(adminId, itemId, FulfillmentChange.builder().withStatus(step).build());
        }

        var deliveredAt = now().minusHours(hoursAgo);
        jdbc.update("UPDATE dbo.PhysicalFulfillment SET DeliveredAt = ?, UpdatedAt = ? WHERE OrderItemId = ?",
            deliveredAt, deliveredAt, itemId);

        return deliveredAt;
    }

    // One physical item per quantity given, each from its own Artist, paid through VNPay with shipping and tax.
    private long paidOrder(int... quantities) {
        var assets = Arrays.stream(quantities).mapToObj(quantity -> listingAsset()).toList();
        var units = BigDecimal.valueOf(Arrays.stream(quantities).sum());
        var subtotal = unitPrice.multiply(units);
        var orderTax = tax.multiply(units);
        var total = subtotal.add(orderTax).add(shipping);
        var orderId = jdbc.queryForObject("INSERT INTO dbo.MarketplaceOrder (CustomerId, Status, CreatedAt,"
                + " ExpiresAt, ItemsSubtotal, ShippingAmount, TaxAmount) OUTPUT INSERTED.OrderId"
                + " VALUES (?, 'PENDING_PAYMENT', ?, ?, ?, ?, ?)",
            Long.class, buyerId, now().minusMinutes(40), now().plusMinutes(30).plusSeconds(1), subtotal, shipping,
            orderTax);
        jdbc.update("INSERT INTO dbo.Payment (OrderId, Amount) VALUES (?, ?)", orderId, total);

        for (var index = 0; index < quantities.length; index++) {
            physicalItem(orderId, assets.get(index), quantities[index]);
        }

        var ref = attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId());
        paymentService.handleVnpayNotification(success(ref, total.multiply(BigDecimal.valueOf(100)).toPlainString()));

        return orderId;
    }

    private void physicalItem(long orderId, ListingAsset asset, int quantity) {
        var itemId = jdbc.queryForObject("INSERT INTO dbo.OrderItem (OrderId, ListingId, ListingType, ArtworkId,"
                + " ArtistId, ArtworkTitle, ListingName, ArtistName, Specifications, Quantity, UnitPrice,"
                + " ManufacturingCostPerUnit, MarkupPerUnit, FeeRate, FeeAmount, ArtistProceeds, TaxAmount)"
                + " OUTPUT INSERTED.OrderItemId VALUES (?, ?, 'PHYSICAL', ?, ?, N'Sunset', N'Sunset A4 print',"
                + " N'Painter', ?, ?, 110000, 80000, 30000, 0, 0, ?, ?)",
            Long.class, orderId, asset.getListingId(), artworkOf(asset), artistOf(asset), a4Specifications, quantity,
            BigDecimal.valueOf(30000L * quantity), tax.multiply(BigDecimal.valueOf(quantity)));
        jdbc.update("INSERT INTO dbo.OrderItemAsset (OrderItemId, ListingId, ListingAssetId, FileId)"
            + " VALUES (?, ?, ?, ?)", itemId, asset.getListingId(), asset.getListingAssetId(), asset.getFileId());
    }

    // A published physical Listing of a new Artist Profile, with one protected file whose bytes exist.
    private ListingAsset listingAsset() {
        StoredFileInfo stored = fileService.storeProtected(new NewFile("sunset.png", "image/png", fileBytes));
        storageKeys.add(stored.getStorageKey());
        var artist = artistRepository.saveAndFlush(new ArtistProfile(registerUser(), "Painter", "ACTIVE", false,
            adminId(), now(), null));
        var artwork = artworkRepository.saveAndFlush(new Artwork(artist.getArtistId(), "Sunset", null, "2D",
            "PUBLISHED", now(), now(), null));
        Listing listing = listingRepository.saveAndFlush(new Listing(artwork.getArtworkId(), "PHYSICAL",
            "Sunset A4 print", null, "DRAFT", "VND", unitPrice, new BigDecimal("80000"), new BigDecimal("30000"),
            a4Specifications, false, now(), null, null));
        var asset = listingAssetRepository.saveAndFlush(new ListingAsset(listing.getListingId(), stored.getFileId(),
            0, now()));
        jdbc.update("UPDATE dbo.Listing SET Status = 'PUBLISHED', PublishedAt = ? WHERE ListingId = ?", now(),
            listing.getListingId());

        return asset;
    }

    private long artworkOf(ListingAsset asset) {
        return listingRepository.findById(asset.getListingId()).orElseThrow().getArtworkId();
    }

    private long artistOf(ListingAsset asset) {
        return artworkRepository.findById(artworkOf(asset)).orElseThrow().getArtistId();
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

    private List<Long> items(long orderId) {
        return jdbc.queryForList("SELECT OrderItemId FROM dbo.OrderItem WHERE OrderId = ? ORDER BY OrderItemId",
            Long.class, orderId);
    }

    private long paymentIdOf(long orderId) {
        return jdbc.queryForObject("SELECT PaymentId FROM dbo.Payment WHERE OrderId = ?", Long.class, orderId);
    }

    private String requestStatus(long requestId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.RefundRequest WHERE RefundRequestId = ?", String.class,
            requestId);
    }

    private int transfers(long requestId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.RefundTransfer WHERE RefundRequestId = ?",
            Integer.class, requestId);
    }

    private long transferId(long requestId) {
        return jdbc.queryForObject("SELECT RefundTransferId FROM dbo.RefundTransfer WHERE RefundRequestId = ?",
            Long.class, requestId);
    }

    private BigDecimal transferAmount(long requestId) {
        return jdbc.queryForObject("SELECT Amount FROM dbo.RefundTransfer WHERE RefundRequestId = ?",
            BigDecimal.class, requestId);
    }

    private List<Long> shippingRefunds(long orderId) {
        return jdbc.queryForList("SELECT RefundRequestId FROM dbo.ShippingRefund WHERE OrderId = ?", Long.class,
            orderId);
    }

    private BigDecimal shippingAmount(long orderId) {
        return jdbc.queryForObject("SELECT Amount FROM dbo.ShippingRefund WHERE OrderId = ?", BigDecimal.class,
            orderId);
    }

    private String fulfillmentStatus(long orderItemId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.PhysicalFulfillment WHERE OrderItemId = ?", String.class,
            orderItemId);
    }

    private List<String> events(long orderItemId) {
        return jdbc.queryForList("SELECT ToStatus FROM dbo.FulfillmentEvent WHERE OrderItemId = ? ORDER BY EventId",
            String.class, orderItemId);
    }

    private long proceedsId(long orderItemId) {
        return jdbc.queryForObject("SELECT ProceedsId FROM dbo.ArtistProceeds WHERE OrderItemId = ?", Long.class,
            orderItemId);
    }

    private String proceedsStatus(long orderItemId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.ArtistProceeds WHERE OrderItemId = ?", String.class,
            orderItemId);
    }

    private int releaseLines(long orderItemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.PayoutLine l JOIN dbo.ArtistProceeds p"
            + " ON p.ProceedsId = l.ProceedsId WHERE p.OrderItemId = ?", Integer.class, orderItemId);
    }

    private int entitlements(long orderItemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.DigitalEntitlement WHERE OrderItemId = ?",
            Integer.class, orderItemId);
    }

    private List<String> audits(long requestId) {
        return jdbc.queryForList("SELECT ActionType FROM dbo.AdminAudit WHERE TargetType = 'REFUND_REQUEST'"
            + " AND TargetId = ? ORDER BY AuditId", String.class, requestId);
    }

    private List<Long> transactions(String type, String sourceType, long sourceId) {
        return jdbc.queryForList("SELECT TransactionId FROM dbo.LedgerTransaction WHERE Type = ? AND SourceType = ?"
            + " AND SourceId = ?", Long.class, type, sourceType, sourceId);
    }

    private Map<String, BigDecimal> accountLines(long transactionId) {
        return jdbc.queryForList("SELECT Account, SUM(Amount) AS Amount FROM dbo.LedgerEntry WHERE TransactionId = ?"
                + " GROUP BY Account", transactionId).stream()
            .collect(Collectors.toMap(row -> (String) row.get("Account"), row -> (BigDecimal) row.get("Amount")));
    }

    private BigDecimal artistBalance(long orderItemId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(Amount), 0) FROM dbo.LedgerEntry WHERE OrderItemId = ?"
            + " AND Account IN ('ARTIST_HELD', 'ARTIST_AVAILABLE', 'ARTIST_RELEASING')", BigDecimal.class,
            orderItemId);
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

    private long adminId() {
        return existingAdmin().orElseGet(this::newAdmin).getStaffId();
    }

    private Optional<StaffAccount> existingAdmin() {
        return staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive())
            .findFirst();
    }

    private StaffAccount newAdmin() {
        return staffRepository.saveAndFlush(new StaffAccount("f15-test-" + UUID.randomUUID(),
            "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now()));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
