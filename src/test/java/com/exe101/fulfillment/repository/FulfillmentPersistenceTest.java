package com.exe101.fulfillment.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.artwork.repository.ArtworkRepository;
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
import com.exe101.listing.repository.ListingAssetRepository;
import com.exe101.listing.repository.ListingRepository;
import com.exe101.payment.config.VnpayProperties;
import com.exe101.payment.model.EventOutcome;
import com.exe101.payment.model.VnpayReply;
import com.exe101.payment.service.PaymentService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

// Fulfillment follows a committed payment, and committed Orders and entitlements can never be deleted (immutability
// triggers). So this runs only against the disposable test database (database/test/reset_test_database.sql),
// selected by TEST_DB_URL, and leaves its rows there. Each test uses a new buyer, seller and Order; bytes written to
// storage are deleted afterwards. The scheduled jobs are slowed down so only the tests run them.
@SpringBootTest(properties = {"app.orders.expiry-interval=PT1H", "app.fulfillment.grant-interval=PT1H"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class FulfillmentPersistenceTest {
    private static String a4Specifications = "Fine-art print, A4 (210 x 297 mm), 230 gsm matte paper";
    private static byte[] fileBytes = "purchased-file".getBytes(StandardCharsets.UTF_8);
    private static long blockedMillis = 500;
    private static long waitSeconds = 30;
    @Autowired
    private PaymentService paymentService;
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
    void paid_settlement_creates_fulfillment_ok() throws Exception {
        // given
        var digital = listingAsset("DIGITAL");
        var physical = listingAsset("PHYSICAL");
        var orderId = pendingOrder("160000", 30);
        var digitalItemId = digitalItem(orderId, digital);
        var physicalItemId = physicalItem(orderId, physical);
        var ref = attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId());

        // when
        var result = paymentService.handleVnpayNotification(success(ref, "16000000"));

        // then
        assertAll(
            () -> assertThat(result.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(orderStatus(orderId)).isEqualTo("PAID"),
            () -> assertThat(entitlements(digitalItemId)).isEqualTo(1),
            () -> assertThat(fulfillmentStatus(physicalItemId)).isEqualTo("PENDING"),
            () -> assertThat(fulfillments(digitalItemId)).isZero(),
            () -> assertThat(entitlements(physicalItemId)).isZero(),
            () -> assertThat(events(physicalItemId)).containsExactly("PENDING")
        );
    }

    @Test
    void exception_settlement_creates_nothing_ok() throws Exception {
        // given
        var physical = listingAsset("PHYSICAL");
        var orderId = pendingOrder("110000", -1);
        var itemId = physicalItem(orderId, physical);
        var ref = insertedAttempt(orderId);

        // when
        var result = paymentService.handleVnpayNotification(success(ref, "11000000"));

        // then
        assertAll(
            () -> assertThat(result.getOutcome()).isEqualTo(EventOutcome.EXCEPTION),
            () -> assertThat(orderStatus(orderId)).isEqualTo("PENDING_PAYMENT"),
            () -> assertThat(fulfillments(itemId)).isZero(),
            () -> assertThat(events(itemId)).isEmpty()
        );
    }

    @Test
    void window_grant_persists_once_ok() throws Exception {
        // given
        var itemId = deliveredItem(49);

        // when
        returnWindowJob.grantClosedWindows();
        var granted = entitlement(itemId);
        returnWindowJob.grantClosedWindows();

        // then
        assertAll(
            () -> assertThat(entitlements(itemId)).isEqualTo(1),
            () -> assertThat(entitlement(itemId)).isEqualTo(granted),
            () -> assertThat(entitlementCustomer(itemId)).isEqualTo(buyerId),
            () -> assertThat(grantedAssets(itemId)).isEqualTo(recordedAssets(itemId)),
            () -> assertThat(grantedAssets(itemId)).isNotEmpty()
        );
    }

    @Test
    void concurrent_window_grants_ok() throws Exception {
        // given
        var itemId = deliveredItem(49);
        var granted = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The first run grants and is held uncommitted while a second run arrives for the same item.
            Future<Boolean> first = executor.submit(() -> inTransaction(() -> {
                var outcome = fulfillmentService.grantIfWindowClosed(itemId);
                granted.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return outcome;
            }));
            granted.await(waitSeconds, TimeUnit.SECONDS);
            Future<Boolean> concurrent = executor.submit(() -> fulfillmentService.grantIfWindowClosed(itemId));
            var concurrentWaited = isBlocked(concurrent);
            release.countDown();
            var firstResult = first.get(waitSeconds, TimeUnit.SECONDS);
            var concurrentResult = concurrent.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(concurrentWaited).isTrue(),
                () -> assertThat(firstResult).isTrue(),
                () -> assertThat(concurrentResult).isFalse(),
                () -> assertThat(entitlements(itemId)).isEqualTo(1),
                () -> assertThat(grantedAssets(itemId)).isEqualTo(recordedAssets(itemId))
            );
        }
    }

    @Test
    void delivered_without_timestamp_ko() throws Exception {
        // given
        // The delivery and its event are written, then the same transaction leaves DELIVERED without DeliveredAt.
        var itemId = paidPhysicalItem();
        advanceTo(itemId, FulfillmentStatus.SHIPPED);
        var eventsBefore = events(itemId);
        var deliver = FulfillmentChange.builder().withStatus(FulfillmentStatus.DELIVERED).build();
        var adminId = admin().getStaffId();

        // when / then
        assertThatThrownBy(() -> inTransaction(() -> {
            fulfillmentService.advance(adminId, itemId, deliver);
            return jdbc.update("UPDATE dbo.PhysicalFulfillment SET DeliveredAt = NULL WHERE OrderItemId = ?", itemId);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertAll(
            () -> assertThat(fulfillmentStatus(itemId)).isEqualTo("SHIPPED"),
            () -> assertThat(deliveredAt(itemId)).isEmpty(),
            () -> assertThat(events(itemId)).isEqualTo(eventsBefore)
        );
    }

    @Test
    void list_reads_shipping_snapshot_ok() throws Exception {
        // given
        // The snapshot is written before payment, as Order creation does; it can never be changed afterwards.
        var physical = listingAsset("PHYSICAL");
        var orderId = pendingOrder("110000", 30);
        var itemId = physicalItem(orderId, physical);
        jdbc.update("INSERT INTO dbo.OrderShippingAddress (OrderId, RecipientName, Phone, Line1, Ward, District,"
                + " ProvinceCode, ProvinceName, CountryCode) VALUES (?, ?, ?, ?, ?, ?, '01', ?, 'VN')", orderId,
            "Nguyễn Văn A", "0912345678", "1 Tràng Tiền", "Tràng Tiền", "Hoàn Kiếm", "Hà Nội");
        var ref = attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId());
        paymentService.handleVnpayNotification(success(ref, "11000000"));

        // when
        var rows = fulfillmentService.list(FulfillmentStatus.PENDING);

        // then
        var row = rows.stream().filter(fulfillment -> fulfillment.getOrderItemId().equals(itemId)).findFirst()
            .orElseThrow();
        var address = row.getShippingAddress();
        assertAll(
            () -> assertThat(row.getOrderId()).isEqualTo(orderId),
            () -> assertThat(row.getArtworkTitle()).isEqualTo("Sunset"),
            () -> assertThat(row.getListingName()).isEqualTo("Sunset A4 print"),
            () -> assertThat(row.getSpecifications()).isEqualTo(a4Specifications),
            () -> assertThat(row.getQuantity()).isEqualTo(1),
            () -> assertThat(address.getRecipientName()).isEqualTo("Nguyễn Văn A"),
            () -> assertThat(address.getPhone()).isEqualTo("0912345678"),
            () -> assertThat(address.getLine1()).isEqualTo("1 Tràng Tiền"),
            () -> assertThat(address.getWard()).isEqualTo("Tràng Tiền"),
            () -> assertThat(address.getDistrict()).isEqualTo("Hoàn Kiếm"),
            () -> assertThat(address.getProvinceName()).isEqualTo("Hà Nội"),
            () -> assertThat(address.getCountryCode()).isEqualTo("VN")
        );
    }

    private Long registerUser() {
        return customerService.register(new CustomerRegistration(
            "f13-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Buyer")).getUserId();
    }

    // A physical item paid through VNPay, so its fulfillment starts the way it does in production.
    private long paidPhysicalItem() {
        var physical = listingAsset("PHYSICAL");
        var orderId = pendingOrder("110000", 30);
        var itemId = physicalItem(orderId, physical);
        var ref = attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId());
        paymentService.handleVnpayNotification(success(ref, "11000000"));

        return itemId;
    }

    // Delivered by the Admin, then moved back in time so its return window has already closed.
    private long deliveredItem(int hoursAgo) {
        var itemId = paidPhysicalItem();
        advanceTo(itemId, FulfillmentStatus.DELIVERED);
        var deliveredAt = now().minusHours(hoursAgo);
        jdbc.update("UPDATE dbo.PhysicalFulfillment SET DeliveredAt = ?, UpdatedAt = ? WHERE OrderItemId = ?",
            deliveredAt, deliveredAt, itemId);

        return itemId;
    }

    private void advanceTo(long itemId, FulfillmentStatus target) {
        var adminId = admin().getStaffId();
        var steps = List.of(FulfillmentStatus.IN_PRODUCTION, FulfillmentStatus.READY_TO_SHIP,
            FulfillmentStatus.SHIPPED, FulfillmentStatus.DELIVERED);

        for (var step : steps.subList(0, steps.indexOf(target) + 1)) {
            fulfillmentService.advance(adminId, itemId, FulfillmentChange.builder().withStatus(step).build());
        }
    }

    // A published Listing of another Customer's Artist Profile, with one protected file whose bytes exist.
    private ListingAsset listingAsset(String listingType) {
        StoredFileInfo stored = fileService.storeProtected(new NewFile("sunset.png", "image/png", fileBytes));
        storageKeys.add(stored.getStorageKey());
        var listing = draftListing(listingType);
        var asset = listingAssetRepository.saveAndFlush(new ListingAsset(listing.getListingId(), stored.getFileId(),
            0, now()));
        jdbc.update("UPDATE dbo.Listing SET Status = 'PUBLISHED', PublishedAt = ? WHERE ListingId = ?", now(),
            listing.getListingId());

        return asset;
    }

    private Listing draftListing(String listingType) {
        var artist = artistRepository.saveAndFlush(new ArtistProfile(registerUser(), "Painter", "ACTIVE", false,
            admin().getStaffId(), now(), null));
        var artwork = artworkRepository.saveAndFlush(new Artwork(artist.getArtistId(), "Sunset", null, "2D",
            "PUBLISHED", now(), now(), null));

        if ("PHYSICAL".equals(listingType)) {
            return listingRepository.saveAndFlush(new Listing(artwork.getArtworkId(), "PHYSICAL", "Sunset A4 print",
                null, "DRAFT", "VND", new BigDecimal("110000"), new BigDecimal("80000"), new BigDecimal("30000"),
                a4Specifications, false, now(), null, null));
        }

        return listingRepository.saveAndFlush(new Listing(artwork.getArtworkId(), "DIGITAL", "Sunset download", null,
            "DRAFT", "VND", new BigDecimal("50000"), null, null, null, false, now(), null, null));
    }

    private long pendingOrder(String amount, int expiresMinutes) {
        var orderId = jdbc.queryForObject("INSERT INTO dbo.MarketplaceOrder (CustomerId, Status, CreatedAt,"
                + " ExpiresAt, ItemsSubtotal) OUTPUT INSERTED.OrderId VALUES (?, 'PENDING_PAYMENT', ?, ?, ?)",
            Long.class, buyerId, now().minusMinutes(40), now().plusMinutes(expiresMinutes).plusSeconds(1),
            new BigDecimal(amount));
        jdbc.update("INSERT INTO dbo.Payment (OrderId, Amount) VALUES (?, ?)", orderId, new BigDecimal(amount));

        return orderId;
    }

    private long digitalItem(long orderId, ListingAsset asset) {
        var itemId = jdbc.queryForObject("INSERT INTO dbo.OrderItem (OrderId, ListingId, ListingType, ArtworkId,"
                + " ArtistId, ArtworkTitle, ListingName, ArtistName, Quantity, UnitPrice, FeeRate, FeeAmount,"
                + " ArtistProceeds) OUTPUT INSERTED.OrderItemId VALUES (?, ?, 'DIGITAL', ?, ?, N'Sunset',"
                + " N'Sunset download', N'Painter', 1, 50000, 0, 0, 50000)",
            Long.class, orderId, asset.getListingId(), artworkOf(asset), artistOf(asset));
        recordAsset(itemId, asset);

        return itemId;
    }

    private long physicalItem(long orderId, ListingAsset asset) {
        var itemId = jdbc.queryForObject("INSERT INTO dbo.OrderItem (OrderId, ListingId, ListingType, ArtworkId,"
                + " ArtistId, ArtworkTitle, ListingName, ArtistName, Specifications, Quantity, UnitPrice,"
                + " ManufacturingCostPerUnit, MarkupPerUnit, FeeRate, FeeAmount, ArtistProceeds)"
                + " OUTPUT INSERTED.OrderItemId VALUES (?, ?, 'PHYSICAL', ?, ?, N'Sunset', N'Sunset A4 print',"
                + " N'Painter', ?, 1, 110000, 80000, 30000, 0, 0, 30000)",
            Long.class, orderId, asset.getListingId(), artworkOf(asset), artistOf(asset), a4Specifications);
        recordAsset(itemId, asset);

        return itemId;
    }

    private void recordAsset(long orderItemId, ListingAsset asset) {
        jdbc.update("INSERT INTO dbo.OrderItemAsset (OrderItemId, ListingId, ListingAssetId, FileId)"
            + " VALUES (?, ?, ?, ?)", orderItemId, asset.getListingId(), asset.getListingAssetId(), asset.getFileId());
    }

    private long artworkOf(ListingAsset asset) {
        return listingRepository.findById(asset.getListingId()).orElseThrow().getArtworkId();
    }

    private long artistOf(ListingAsset asset) {
        return artworkRepository.findById(artworkOf(asset)).orElseThrow().getArtistId();
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

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.MarketplaceOrder WHERE OrderId = ?", String.class,
            orderId);
    }

    private int fulfillments(long orderItemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.PhysicalFulfillment WHERE OrderItemId = ?",
            Integer.class, orderItemId);
    }

    private String fulfillmentStatus(long orderItemId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.PhysicalFulfillment WHERE OrderItemId = ?", String.class,
            orderItemId);
    }

    private Optional<OffsetDateTime> deliveredAt(long orderItemId) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT DeliveredAt FROM dbo.PhysicalFulfillment"
            + " WHERE OrderItemId = ?", OffsetDateTime.class, orderItemId));
    }

    private List<String> events(long orderItemId) {
        return jdbc.queryForList("SELECT ToStatus FROM dbo.FulfillmentEvent WHERE OrderItemId = ? ORDER BY EventId",
            String.class, orderItemId);
    }

    private int entitlements(long orderItemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.DigitalEntitlement WHERE OrderItemId = ?",
            Integer.class, orderItemId);
    }

    private Map<String, Object> entitlement(long orderItemId) {
        return jdbc.queryForMap("SELECT EntitlementId, CustomerId, GrantedAt FROM dbo.DigitalEntitlement"
            + " WHERE OrderItemId = ?", orderItemId);
    }

    private long entitlementCustomer(long orderItemId) {
        return jdbc.queryForObject("SELECT CustomerId FROM dbo.DigitalEntitlement WHERE OrderItemId = ?",
            Long.class, orderItemId);
    }

    private List<Long> grantedAssets(long orderItemId) {
        return jdbc.queryForList("SELECT ListingAssetId FROM dbo.EntitlementAsset WHERE OrderItemId = ?"
            + " ORDER BY ListingAssetId", Long.class, orderItemId);
    }

    private List<Long> recordedAssets(long orderItemId) {
        return jdbc.queryForList("SELECT ListingAssetId FROM dbo.OrderItemAsset WHERE OrderItemId = ?"
            + " ORDER BY ListingAssetId", Long.class, orderItemId);
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

    private StaffAccount admin() {
        return existingAdmin().orElseGet(this::newAdmin);
    }

    private Optional<StaffAccount> existingAdmin() {
        return staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive())
            .findFirst();
    }

    private StaffAccount newAdmin() {
        return staffRepository.saveAndFlush(new StaffAccount("f13-test-" + UUID.randomUUID(),
            "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now()));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
