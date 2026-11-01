package com.exe101.artist.repository;

import com.exe101.address.model.AddressDetails;
import com.exe101.address.service.AddressService;
import com.exe101.artist.model.ArtistDeactivation;
import com.exe101.artist.service.ArtistService;
import com.exe101.artwork.model.ArtworkStatus;
import com.exe101.artwork.model.StatusChange;
import com.exe101.artwork.repository.ArtworkRepository;
import com.exe101.artwork.service.ArtworkService;
import com.exe101.cart.model.CartItemChange;
import com.exe101.cart.service.CartService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.Artwork;
import com.exe101.entity.Listing;
import com.exe101.entity.ListingAsset;
import com.exe101.entity.StaffAccount;
import com.exe101.entity.SupportedProvince;
import com.exe101.file.config.StorageProperties;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.service.FileService;
import com.exe101.ledger.model.Mismatch;
import com.exe101.ledger.service.LedgerService;
import com.exe101.listing.model.ListingStatus;
import com.exe101.listing.model.ListingStatusChange;
import com.exe101.listing.repository.ListingAssetRepository;
import com.exe101.listing.repository.ListingRepository;
import com.exe101.listing.service.ListingService;
import com.exe101.order.model.CommitLine;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderCommit;
import com.exe101.order.service.OrderService;
import com.exe101.payment.config.VnpayProperties;
import com.exe101.payment.model.NotificationResult;
import com.exe101.payment.model.VnpayReply;
import com.exe101.payment.service.PaymentService;
import com.exe101.proceeds.model.Proceeds;
import com.exe101.proceeds.service.ProceedsService;
import com.exe101.province.repository.ProvinceRepository;
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
import java.util.concurrent.ThreadLocalRandom;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

// Deactivation is checked against real checkouts and payments, and committed Orders can never be deleted
// (immutability triggers). So this runs only against the disposable test database
// (database/test/reset_test_database.sql), selected by TEST_DB_URL, and leaves its rows there. Each test uses new
// Users, Artists and Listings; bytes written to storage are deleted afterwards. The scheduled jobs are slowed down
// so they do not run during a test.
@SpringBootTest(properties = {"app.orders.expiry-interval=PT1H", "app.fulfillment.grant-interval=PT1H"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ArtistDeactivationPersistenceTest {
    private static String a4Specifications = "Fine-art print, A4 (210 x 297 mm), 230 gsm matte paper";
    private static byte[] fileBytes = "purchased-file".getBytes(StandardCharsets.UTF_8);
    private static String clientIp = "203.0.113.7";
    private static long blockedMillis = 500;
    private static long waitSeconds = 30;
    @Autowired
    private ArtistService artistService;
    @Autowired
    private ArtworkService artworkService;
    @Autowired
    private ListingService listingService;
    @Autowired
    private CartService cartService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private ProceedsService proceedsService;
    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private AddressService addressService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private FileService fileService;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private ArtworkRepository artworkRepository;
    @Autowired
    private ListingRepository listingRepository;
    @Autowired
    private ListingAssetRepository listingAssetRepository;
    @Autowired
    private ProvinceRepository provinceRepository;
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
    private Long adminId;
    private SupportedProvince province;
    private List<String> storageKeys;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
    }

    @BeforeEach
    void setUp() {
        adminId = admin().getStaffId();
        // A test-only code outside the official two-digit range, so it never collides with real rows.
        province = provinceRepository.saveAndFlush(new SupportedProvince(
            "T" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000), "Test Province", true, now()));
        storageKeys = new ArrayList<>();
    }

    @AfterEach
    void tearDown() throws IOException {
        for (var storageKey : storageKeys) {
            Files.deleteIfExists(Path.of(storage.root()).resolve(storageKey));
        }
    }

    @Test
    void deactivation_keeps_history_ok() {
        // given
        var artist = newArtist();

        // when
        var deactivated = artistService.deactivate(adminId, artist.getArtistId(), "Left the team");
        var reinstated = artistService.reinstate(adminId, artist.getArtistId(), "Rejoined");
        var auditsAfterReinstatement = audits(artist.getArtistId());
        var recordsAfterReinstatement = records(artist.getArtistId());
        var again = artistService.deactivate(adminId, artist.getArtistId(), "Left again");

        // then
        var records = records(artist.getArtistId());
        assertAll(
            () -> assertThat(reinstated.getDeactivationId()).isEqualTo(deactivated.getDeactivationId()),
            () -> assertThat(auditsAfterReinstatement).containsExactly("ARTIST_DEACTIVATED", "ARTIST_REINSTATED"),
            () -> assertThat(recordsAfterReinstatement).singleElement()
                .satisfies(record -> assertThat(record.get("ClosedAt")).isNotNull()),
            () -> assertThat(records).hasSize(2),
            () -> assertThat(records.get(0).get("Reason")).isEqualTo("Left the team"),
            () -> assertThat(records.get(0).get("ClosedAt")).isNotNull(),
            () -> assertThat(records.get(1).get("SuspensionId")).isEqualTo(again.getDeactivationId()),
            () -> assertThat(records.get(1).get("Reason")).isEqualTo("Left again"),
            () -> assertThat(records.get(1).get("EndsAt")).isNull(),
            () -> assertThat(records.get(1).get("ClosedAt")).isNull(),
            () -> assertThat(records).extracting(record -> record.get("StaffId")).containsOnly(adminId),
            () -> assertThat(audits(artist.getArtistId()))
                .containsExactly("ARTIST_DEACTIVATED", "ARTIST_REINSTATED", "ARTIST_DEACTIVATED"),
            () -> assertThat(artistStatus(artist.getArtistId())).isEqualTo("SUSPENDED")
        );
    }

    @Test
    void concurrent_deactivation_one_wins_ko() throws Exception {
        // given
        var artistId = newArtist().getArtistId();
        var deactivated = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The first deactivation is held uncommitted while a second one arrives for the same Artist.
            Future<ArtistDeactivation> first = executor.submit(() -> inTransaction(() -> {
                var outcome = artistService.deactivate(adminId, artistId, "First");
                deactivated.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return outcome;
            }));
            deactivated.await(waitSeconds, TimeUnit.SECONDS);
            Future<ArtistDeactivation> second = executor.submit(() ->
                artistService.deactivate(adminId, artistId, "Second"));
            var secondWaited = isBlocked(second);
            release.countDown();
            var firstResult = first.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(secondWaited).isTrue(),
                () -> assertThat(firstResult.getReason()).isEqualTo("First"),
                () -> assertThatThrownBy(() -> second.get(waitSeconds, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(AppException.class)
                    .extracting("error")
                    .isEqualTo(AppErrorMessage.ARTIST_ALREADY_DEACTIVATED),
                () -> assertThat(records(artistId)).singleElement()
                    .satisfies(record -> assertThat(record.get("ClosedAt")).isNull()),
                () -> assertThat(audits(artistId)).containsExactly("ARTIST_DEACTIVATED")
            );
        }
    }

    @Test
    void deactivated_artist_listing_not_purchasable_ko() {
        // given
        var listing = publishedListing(newArtist(), "DIGITAL");
        var buyerId = newUser();
        var addressId = address(buyerId);
        addToCart(buyerId, listing);
        artistService.deactivate(adminId, artistOf(listing), "Left the team");

        // when / then
        assertThatThrownBy(() -> orderService.create(buyerId, confirmedCommit(buyerId, addressId)))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.CHECKOUT_BLOCKED);
        assertThat(orders(buyerId)).isZero();
    }

    @Test
    void reinstated_artist_listing_purchasable_ok() {
        // given
        var listing = publishedListing(newArtist(), "DIGITAL");
        var listingVersion = listingVersion(listing);
        var buyerId = newUser();
        var addressId = address(buyerId);
        artistService.deactivate(adminId, artistOf(listing), "Left the team");
        artistService.reinstate(adminId, artistOf(listing), "Rejoined");
        addToCart(buyerId, listing);

        // when
        var order = orderService.create(buyerId, confirmedCommit(buyerId, addressId));

        // then
        assertAll(
            () -> assertThat(order.getItems()).singleElement()
                .satisfies(item -> assertThat(item.getListingId()).isEqualTo(listing.getListingId())),
            () -> assertThat(listingVersion(listing)).isEqualTo(listingVersion),
            () -> assertThat(artistStatus(artistOf(listing))).isEqualTo("ACTIVE")
        );
    }

    @Test
    void deactivated_artist_cannot_publish_ko() {
        // given
        var artist = newArtist();
        var publishedArtwork = artwork(artist, "PUBLISHED");
        var draftArtwork = artwork(artist, "DRAFT");
        var draftListing = listingRepository.saveAndFlush(new Listing(publishedArtwork.getArtworkId(), "DIGITAL",
            "Sunset download", null, "DRAFT", "VND", new BigDecimal("50000"), null, null, null, false, now(), null,
            null));
        artistService.deactivate(adminId, artist.getArtistId(), "Left the team");

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> listingService.changeStatus(artist.getUserId(),
                    draftListing.getListingId(), new ListingStatusChange(ListingStatus.PUBLISHED, null)))
                .isInstanceOf(AppException.class)
                .extracting("error")
                .isEqualTo(AppErrorMessage.ARTIST_NOT_ACTIVE),
            () -> assertThatThrownBy(() -> artworkService.changeStatus(artist.getUserId(),
                    draftArtwork.getArtworkId(), new StatusChange(ArtworkStatus.PUBLISHED, null)))
                .isInstanceOf(AppException.class)
                .extracting("error")
                .isEqualTo(AppErrorMessage.ARTIST_NOT_ACTIVE),
            () -> assertThat(listingStatus(draftListing.getListingId())).isEqualTo("DRAFT"),
            () -> assertThat(artworkStatus(draftArtwork.getArtworkId())).isEqualTo("DRAFT")
        );
    }

    @Test
    void earlier_order_payable_after_deactivation_ok() {
        // given
        var listing = publishedListing(newArtist(), "PHYSICAL");
        var buyerId = newUser();
        var addressId = address(buyerId);
        addToCart(buyerId, listing);
        var order = orderService.create(buyerId, confirmedCommit(buyerId, addressId));
        var itemId = order.getItems().getFirst().getOrderItemId();
        artistService.deactivate(adminId, artistOf(listing), "Left the team");

        // when
        var outcome = pay(buyerId, order);

        // then
        var paymentId = paymentIdOf(order.getOrderId());
        var reconciliation = ledgerService.reconcile();
        assertAll(
            () -> assertThat(outcome.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(orderStatus(order.getOrderId())).isEqualTo("PAID"),
            () -> assertThat(proceedsStatus(itemId)).isEqualTo("HELD"),
            () -> assertThat(proceedsArtist(itemId)).isEqualTo(artistOf(listing)),
            () -> assertThat(proceedsAmount(itemId)).isEqualByComparingTo("30000"),
            () -> assertThat(paymentLedgerSum(paymentId)).isEqualByComparingTo("0"),
            () -> assertThat(reconciliation.getMismatches()).extracting(Mismatch::getRecordId)
                .doesNotContainAnyElementsOf(List.of(paymentId, itemId, proceedsId(itemId)))
        );
    }

    @Test
    void deactivation_waits_for_order_creation_ok() throws Exception {
        // given
        var listing = publishedListing(newArtist(), "DIGITAL");
        var buyerId = newUser();
        var addressId = address(buyerId);
        addToCart(buyerId, listing);
        var commit = confirmedCommit(buyerId, addressId);
        var created = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The Order holds its Artist's lock until it commits; the deactivation waits for it.
            Future<Order> creator = executor.submit(() -> inTransaction(() -> {
                var order = orderService.create(buyerId, commit);
                created.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return order;
            }));
            created.await(waitSeconds, TimeUnit.SECONDS);
            Future<ArtistDeactivation> deactivation = executor.submit(() ->
                artistService.deactivate(adminId, artistOf(listing), "Left the team"));
            var deactivationWaited = isBlocked(deactivation);
            release.countDown();
            var order = creator.get(waitSeconds, TimeUnit.SECONDS);
            deactivation.get(waitSeconds, TimeUnit.SECONDS);
            var start = paymentService.start(buyerId, order.getOrderId(), clientIp);

            // then
            assertAll(
                () -> assertThat(deactivationWaited).isTrue(),
                () -> assertThat(artistStatus(artistOf(listing))).isEqualTo("SUSPENDED"),
                () -> assertThat(orderStatus(order.getOrderId())).isEqualTo("PENDING_PAYMENT"),
                () -> assertThat(start.getAttemptId()).isNotNull()
            );
        }
    }

    @Test
    void proceeds_release_unchanged_after_deactivation_ok() {
        // given
        var artist = newArtist();
        var listing = publishedListing(artist, "DIGITAL");
        var buyerId = newUser();
        var addressId = address(buyerId);
        addToCart(buyerId, listing);
        var order = orderService.create(buyerId, confirmedCommit(buyerId, addressId));
        var itemId = order.getItems().getFirst().getOrderItemId();
        artistService.deactivate(adminId, artist.getArtistId(), "Left the team");

        // when
        pay(buyerId, order);

        // then
        var proceedsId = proceedsId(itemId);
        assertAll(
            () -> assertThat(proceedsStatus(itemId)).isEqualTo("RESERVED"),
            () -> assertThat(releaseStatuses(proceedsId)).containsExactly("PENDING"),
            () -> assertThat(proceedsService.listOwn(artist.getUserId())).extracting(Proceeds::getOrderItemId)
                .containsExactly(itemId)
        );
    }

    @Test
    void deactivated_artist_keeps_customer_access_ok() {
        // given
        var deactivated = newArtist();
        var otherListing = publishedListing(newArtist(), "DIGITAL");
        artistService.deactivate(adminId, deactivated.getArtistId(), "Left the team");
        var addressId = address(deactivated.getUserId());
        addToCart(deactivated.getUserId(), otherListing);

        // when
        var order = orderService.create(deactivated.getUserId(), confirmedCommit(deactivated.getUserId(), addressId));

        // then
        assertAll(
            () -> assertThat(order.getCustomerId()).isEqualTo(deactivated.getUserId()),
            () -> assertThat(orderStatus(order.getOrderId())).isEqualTo("PENDING_PAYMENT"),
            () -> assertThat(order.getItems()).singleElement()
                .satisfies(item -> assertThat(item.getArtistId()).isEqualTo(artistOf(otherListing)))
        );
    }

    private ArtistProfile newArtist() {
        return artistRepository.saveAndFlush(new ArtistProfile(newUser(), "Painter", "ACTIVE", false, adminId, now(),
            null));
    }

    private Long newUser() {
        return customerService.register(new CustomerRegistration(
            "f03-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Member")).getUserId();
    }

    private Artwork artwork(ArtistProfile artist, String status) {
        var publishedAt = "PUBLISHED".equals(status) ? now() : null;

        return artworkRepository.saveAndFlush(new Artwork(artist.getArtistId(), "Sunset", null, "2D", status,
            publishedAt, now(), null));
    }

    // A published Listing with one protected file whose bytes exist.
    private Listing publishedListing(ArtistProfile artist, String listingType) {
        var artwork = artwork(artist, "PUBLISHED");
        var listing = "PHYSICAL".equals(listingType)
            ? listingRepository.saveAndFlush(new Listing(artwork.getArtworkId(), "PHYSICAL", "Sunset A4 print", null,
                "DRAFT", "VND", new BigDecimal("110000"), new BigDecimal("80000"), new BigDecimal("30000"),
                a4Specifications, false, now(), null, null))
            : listingRepository.saveAndFlush(new Listing(artwork.getArtworkId(), "DIGITAL", "Sunset download", null,
                "DRAFT", "VND", new BigDecimal("50000"), null, null, null, false, now(), null, null));
        StoredFileInfo stored = fileService.storeProtected(new NewFile("sunset.png", "image/png", fileBytes));
        storageKeys.add(stored.getStorageKey());
        listingAssetRepository.saveAndFlush(new ListingAsset(listing.getListingId(), stored.getFileId(), 0, now()));
        jdbc.update("UPDATE dbo.Listing SET Status = 'PUBLISHED', PublishedAt = ? WHERE ListingId = ?", now(),
            listing.getListingId());

        return listingRepository.findById(listing.getListingId()).orElseThrow();
    }

    private Long address(Long userId) {
        return addressService.create(userId, AddressDetails.builder()
            .withRecipientName("Nguyễn Văn A")
            .withPhone("0912345678")
            .withLine1("1 Tràng Tiền")
            .withWard("Phường Hoàn Kiếm")
            .withProvinceCode(province.getProvinceCode())
            .build()).getAddressId();
    }

    private void addToCart(Long userId, Listing listing) {
        cartService.addItem(userId, CartItemChange.builder().withListingId(listing.getListingId())
            .withQuantity(1).build());
    }

    // Built from a fresh preview, as a client sends back what the Customer confirmed.
    private OrderCommit confirmedCommit(Long userId, Long addressId) {
        var preview = cartService.preview(userId, addressId);
        var lines = preview.getLines().stream()
            .map(line -> CommitLine.builder()
                .withListingId(line.getListingId())
                .withQuantity(line.getQuantity())
                .withUnitPrice(line.getUnitPrice())
                .build())
            .toList();

        return OrderCommit.builder()
            .withCartVersion(preview.getCartVersion())
            .withAddressId(addressId)
            .withLines(lines)
            .withTotalAmount(preview.getTotalAmount())
            .build();
    }

    private NotificationResult pay(Long buyerId, Order order) {
        var start = paymentService.start(buyerId, order.getOrderId(), clientIp);
        var amountTimes100 = order.getTotalAmount().movePointRight(2).toBigInteger().toString();

        return paymentService.handleVnpayNotification(success(attemptRef(start.getAttemptId()), amountTimes100));
    }

    private long artistOf(Listing listing) {
        return artworkRepository.findById(listing.getArtworkId()).orElseThrow().getArtistId();
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

    private String artistStatus(long artistId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.ArtistProfile WHERE ArtistId = ?", String.class, artistId);
    }

    private List<Map<String, Object>> records(long artistId) {
        return jdbc.queryForList("SELECT SuspensionId, Reason, StaffId, EndsAt, ClosedAt FROM dbo.ArtistSuspension"
            + " WHERE ArtistId = ? ORDER BY SuspensionId", artistId);
    }

    private List<String> audits(long artistId) {
        return jdbc.queryForList("SELECT ActionType FROM dbo.AdminAudit WHERE TargetType = 'ArtistProfile'"
            + " AND TargetId = ? AND ActionType <> 'ARTIST_PROFILE_GRANTED' ORDER BY AuditId", String.class, artistId);
    }

    private byte[] listingVersion(Listing listing) {
        return jdbc.queryForObject("SELECT RowVer FROM dbo.Listing WHERE ListingId = ?", byte[].class,
            listing.getListingId());
    }

    private String listingStatus(long listingId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.Listing WHERE ListingId = ?", String.class, listingId);
    }

    private String artworkStatus(long artworkId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.Artwork WHERE ArtworkId = ?", String.class, artworkId);
    }

    private int orders(long customerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.MarketplaceOrder WHERE CustomerId = ?", Integer.class,
            customerId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.MarketplaceOrder WHERE OrderId = ?", String.class,
            orderId);
    }

    private long paymentIdOf(long orderId) {
        return jdbc.queryForObject("SELECT PaymentId FROM dbo.Payment WHERE OrderId = ?", Long.class, orderId);
    }

    private BigDecimal paymentLedgerSum(long paymentId) {
        return jdbc.queryForObject("SELECT SUM(e.Amount) FROM dbo.LedgerEntry e JOIN dbo.LedgerTransaction t"
            + " ON t.TransactionId = e.TransactionId WHERE t.SourceType = 'PAYMENT' AND t.SourceId = ?",
            BigDecimal.class, paymentId);
    }

    private long proceedsId(long orderItemId) {
        return jdbc.queryForObject("SELECT ProceedsId FROM dbo.ArtistProceeds WHERE OrderItemId = ?", Long.class,
            orderItemId);
    }

    private String proceedsStatus(long orderItemId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.ArtistProceeds WHERE OrderItemId = ?", String.class,
            orderItemId);
    }

    private long proceedsArtist(long orderItemId) {
        return jdbc.queryForObject("SELECT ArtistId FROM dbo.ArtistProceeds WHERE OrderItemId = ?", Long.class,
            orderItemId);
    }

    private BigDecimal proceedsAmount(long orderItemId) {
        return jdbc.queryForObject("SELECT Amount FROM dbo.ArtistProceeds WHERE OrderItemId = ?", BigDecimal.class,
            orderItemId);
    }

    private List<String> releaseStatuses(long proceedsId) {
        return jdbc.queryForList("SELECT p.Status FROM dbo.Payout p JOIN dbo.PayoutLine l ON l.PayoutId = p.PayoutId"
            + " WHERE l.ProceedsId = ? ORDER BY p.PayoutId", String.class, proceedsId);
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
        return staffRepository.saveAndFlush(new StaffAccount("f03-test-" + UUID.randomUUID(),
            "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now()));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
