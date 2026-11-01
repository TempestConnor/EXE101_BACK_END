package com.exe101.proceeds.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.artwork.repository.ArtworkRepository;
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
import com.exe101.listing.repository.ListingAssetRepository;
import com.exe101.listing.repository.ListingRepository;
import com.exe101.payment.config.VnpayProperties;
import com.exe101.payment.model.EventOutcome;
import com.exe101.payment.model.NotificationResult;
import com.exe101.payment.model.VnpayReply;
import com.exe101.payment.service.PaymentService;
import com.exe101.proceeds.model.Proceeds;
import com.exe101.proceeds.model.Release;
import com.exe101.proceeds.model.ReleaseOutcome;
import com.exe101.proceeds.model.ReleaseStatus;
import com.exe101.proceeds.service.ProceedsService;
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

// Proceeds follow a committed payment, and committed Orders can never be deleted (immutability triggers). So this
// runs only against the disposable test database (database/test/reset_test_database.sql), selected by TEST_DB_URL,
// and leaves its rows there. Each test uses a new buyer, sellers and Order; bytes written to storage are deleted
// afterwards. The scheduled jobs are slowed down so only the tests run them.
@SpringBootTest(properties = {"app.orders.expiry-interval=PT1H", "app.fulfillment.grant-interval=PT1H"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ProceedsPersistenceTest {
    private static String a4Specifications = "Fine-art print, A4 (210 x 297 mm), 230 gsm matte paper";
    private static byte[] fileBytes = "purchased-file".getBytes(StandardCharsets.UTF_8);
    private static long blockedMillis = 500;
    private static long waitSeconds = 30;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private ProceedsService proceedsService;
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
    private ReleaseLineRepository releaseLineRepository;
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
        reset(releaseLineRepository);

        for (var storageKey : storageKeys) {
            Files.deleteIfExists(Path.of(storage.root()).resolve(storageKey));
        }
    }

    @Test
    void duplicate_ipn_creates_one_proceeds_ok() {
        // given
        var digital = listingAsset("DIGITAL");
        var orderId = pendingOrder("50000");
        var itemId = digitalItem(orderId, digital);
        var success = success(attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId()),
            "5000000");

        // when
        var first = paymentService.handleVnpayNotification(success);
        var repeated = paymentService.handleVnpayNotification(success);

        // then
        var proceedsId = proceedsId(itemId);
        assertAll(
            () -> assertThat(first.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(repeated.isDuplicate()).isTrue(),
            () -> assertThat(proceedsCount(itemId)).isEqualTo(1),
            () -> assertThat(proceedsStatus(itemId)).isEqualTo("RESERVED"),
            () -> assertThat(proceedsAmount(itemId)).isEqualByComparingTo("50000"),
            () -> assertThat(releaseIds(proceedsId)).hasSize(1),
            () -> assertThat(activeLines(proceedsId)).isEqualTo(1)
        );
    }

    @Test
    void concurrent_ipn_creates_one_proceeds_ok() throws Exception {
        // given
        // Two attempts of one Order both report success; the second waits for the first and becomes an exception.
        var digital = listingAsset("DIGITAL");
        var orderId = pendingOrder("50000");
        var itemId = digitalItem(orderId, digital);
        var firstSuccess = success(attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7")
            .getAttemptId()), "5000000");
        var secondSuccess = success(attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7")
            .getAttemptId()), "5000000");
        var handled = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            Future<NotificationResult> first = executor.submit(() -> inTransaction(() -> {
                var outcome = paymentService.handleVnpayNotification(firstSuccess);
                handled.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return outcome;
            }));
            handled.await(waitSeconds, TimeUnit.SECONDS);
            Future<NotificationResult> second = executor.submit(() ->
                paymentService.handleVnpayNotification(secondSuccess));
            var secondWaited = isBlocked(second);
            release.countDown();
            var firstResult = first.get(waitSeconds, TimeUnit.SECONDS);
            var secondResult = second.get(waitSeconds, TimeUnit.SECONDS);

            // then
            var proceedsId = proceedsId(itemId);
            assertAll(
                () -> assertThat(secondWaited).isTrue(),
                () -> assertThat(firstResult.getReply()).isEqualTo(VnpayReply.CONFIRMED),
                () -> assertThat(secondResult.getOutcome()).isEqualTo(EventOutcome.EXCEPTION),
                () -> assertThat(proceedsCount(itemId)).isEqualTo(1),
                () -> assertThat(releaseIds(proceedsId)).hasSize(1),
                () -> assertThat(activeLines(proceedsId)).isEqualTo(1)
            );
        }
    }

    @Test
    void proceeds_failure_rolls_back_payment_success_ko() {
        // given
        // The proceeds and the release are written before the line, so the failure comes after both.
        var digital = listingAsset("DIGITAL");
        var orderId = pendingOrder("50000");
        var itemId = digitalItem(orderId, digital);
        var artistId = artistOf(digital);
        var success = success(attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId()),
            "5000000");
        doThrow(new IllegalStateException("release line insert failed")).when(releaseLineRepository)
            .saveAndFlush(any());

        // when
        assertThatThrownBy(() -> paymentService.handleVnpayNotification(success))
            .isInstanceOf(IllegalStateException.class);
        var orderAfterFailure = orderStatus(orderId);
        var proceedsAfterFailure = proceedsCount(itemId);
        var releasesAfterFailure = artistReleases(artistId);
        var entitlementsAfterFailure = entitlements(itemId);
        reset(releaseLineRepository);
        var retried = paymentService.handleVnpayNotification(success);

        // then
        assertAll(
            () -> assertThat(orderAfterFailure).isEqualTo("PENDING_PAYMENT"),
            () -> assertThat(proceedsAfterFailure).isZero(),
            () -> assertThat(releasesAfterFailure).isZero(),
            () -> assertThat(entitlementsAfterFailure).isZero(),
            () -> assertThat(retried.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(orderStatus(orderId)).isEqualTo("PAID"),
            () -> assertThat(proceedsCount(itemId)).isEqualTo(1),
            () -> assertThat(proceedsStatus(itemId)).isEqualTo("RESERVED"),
            () -> assertThat(artistReleases(artistId)).isEqualTo(1)
        );
    }

    @Test
    void window_close_releases_exact_snapshot_amount_ok() {
        // given
        // Two prints at a 30 000 markup; the Artist raises the markup after the Order, which must not count.
        var physical = listingAsset("PHYSICAL");
        var orderId = pendingOrder("220000");
        var itemId = physicalItem(orderId, physical);
        var success = success(attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId()),
            "22000000");
        paymentService.handleVnpayNotification(success);
        var statusAfterPayment = proceedsStatus(itemId);
        jdbc.update("UPDATE dbo.Listing SET ArtistMarkup = 45000, Price = 125000 WHERE ListingId = ?",
            physical.getListingId());
        deliver(itemId, 49);

        // when
        returnWindowJob.grantClosedWindows();

        // then
        var proceedsId = proceedsId(itemId);
        var releaseId = releaseIds(proceedsId).getFirst();
        assertAll(
            () -> assertThat(statusAfterPayment).isEqualTo("HELD"),
            () -> assertThat(proceedsStatus(itemId)).isEqualTo("RESERVED"),
            () -> assertThat(proceedsAmount(itemId)).isEqualByComparingTo("60000"),
            () -> assertThat(eligibleAt(itemId)).isPresent(),
            () -> assertThat(entitlements(itemId)).isEqualTo(1),
            () -> assertThat(releaseIds(proceedsId)).hasSize(1),
            () -> assertThat(releaseStatus(releaseId)).isEqualTo("PENDING"),
            () -> assertThat(releaseAmount(releaseId)).isEqualByComparingTo("60000"),
            () -> assertThat(lineAmounts(releaseId)).singleElement()
                .satisfies(amount -> assertThat(amount).isEqualByComparingTo("60000")),
            () -> assertThat(releaseArtist(releaseId)).isEqualTo(artistOf(physical))
        );
    }

    @Test
    void concurrent_outcome_recording_settles_once_ok() throws Exception {
        // given
        var itemId = paidDigitalItem();
        var proceedsId = proceedsId(itemId);
        var releaseId = releaseIds(proceedsId).getFirst();
        var adminId = admin().getStaffId();
        var firstReference = "test-" + UUID.randomUUID();
        var secondReference = "test-" + UUID.randomUUID();
        var recorded = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The first outcome is recorded and held uncommitted while a second one arrives for the same release.
            Future<Release> first = executor.submit(() -> inTransaction(() -> {
                var outcome = proceedsService.recordOutcome(adminId, releaseId, paid(firstReference));
                recorded.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return outcome;
            }));
            recorded.await(waitSeconds, TimeUnit.SECONDS);
            Future<Release> second = executor.submit(() ->
                proceedsService.recordOutcome(adminId, releaseId, paid(secondReference)));
            var secondWaited = isBlocked(second);
            release.countDown();
            var firstResult = first.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(secondWaited).isTrue(),
                () -> assertThat(firstResult.getStatus()).isEqualTo(ReleaseStatus.PAID),
                () -> assertThatThrownBy(() -> second.get(waitSeconds, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(AppException.class)
                    .extracting("error")
                    .isEqualTo(AppErrorMessage.RELEASE_STATUS_CHANGE_NOT_ALLOWED),
                () -> assertThat(releaseStatus(releaseId)).isEqualTo("PAID"),
                () -> assertThat(releaseReference(releaseId)).isEqualTo(firstReference),
                () -> assertThat(proceedsStatus(itemId)).isEqualTo("PAID"),
                () -> assertThat(audits(releaseId)).containsExactly("RELEASE_STATUS_CHANGED")
            );
        }
    }

    @Test
    void rerelease_after_failure_keeps_one_active_line_ok() {
        // given
        var itemId = paidDigitalItem();
        var proceedsId = proceedsId(itemId);
        var failedId = releaseIds(proceedsId).getFirst();
        var adminId = admin().getStaffId();
        proceedsService.recordOutcome(adminId, failedId, ReleaseOutcome.builder()
            .withStatus(ReleaseStatus.FAILED).withReason("Account closed").build());
        var statusAfterFailure = proceedsStatus(itemId);

        // when
        var renewed = proceedsService.release(adminId, proceedsId);

        // then
        assertAll(
            () -> assertThat(statusAfterFailure).isEqualTo("ELIGIBLE"),
            () -> assertThat(renewed.getStatus()).isEqualTo(ReleaseStatus.PENDING),
            () -> assertThat(renewed.getAmount()).isEqualByComparingTo("50000"),
            () -> assertThat(releaseIds(proceedsId)).containsExactly(failedId, renewed.getPayoutId()),
            () -> assertThat(releaseStatus(failedId)).isEqualTo("FAILED"),
            () -> assertThat(activeLines(proceedsId)).isEqualTo(1),
            () -> assertThat(proceedsStatus(itemId)).isEqualTo("RESERVED"),
            () -> assertThat(audits(renewed.getPayoutId())).containsExactly("RELEASE_CREATED"),
            () -> assertThatThrownBy(() -> proceedsService.release(adminId, proceedsId))
                .isInstanceOf(AppException.class)
                .extracting("error")
                .isEqualTo(AppErrorMessage.PROCEEDS_NOT_RELEASABLE)
        );
    }

    @Test
    void artist_lists_only_own_proceeds_ok() {
        // given
        // One Order buys from two Artists.
        var first = listingAsset("DIGITAL");
        var second = listingAsset("DIGITAL");
        var orderId = pendingOrder("100000");
        var firstItemId = digitalItem(orderId, first);
        var secondItemId = digitalItem(orderId, second);
        var success = success(attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId()),
            "10000000");
        paymentService.handleVnpayNotification(success);
        var firstArtistUserId = artistRepository.findById(artistOf(first)).orElseThrow().getUserId();

        // when
        var proceeds = proceedsService.listOwn(firstArtistUserId);
        var releases = proceedsService.listOwnReleases(firstArtistUserId);

        // then
        assertAll(
            () -> assertThat(proceeds).extracting(Proceeds::getOrderItemId).containsExactly(firstItemId),
            () -> assertThat(proceeds).extracting(Proceeds::getArtistId).containsOnly(artistOf(first)),
            () -> assertThat(releases).extracting(Release::getProceedsId).containsExactly(proceedsId(firstItemId)),
            () -> assertThat(releases).extracting(Release::getProceedsId).doesNotContain(proceedsId(secondItemId))
        );
    }

    private Long registerUser() {
        return customerService.register(new CustomerRegistration(
            "f14-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Buyer")).getUserId();
    }

    private long paidDigitalItem() {
        var digital = listingAsset("DIGITAL");
        var orderId = pendingOrder("50000");
        var itemId = digitalItem(orderId, digital);
        var ref = attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId());
        paymentService.handleVnpayNotification(success(ref, "5000000"));

        return itemId;
    }

    // Delivered by the Admin, then moved back in time so its return window has already closed.
    private void deliver(long itemId, int hoursAgo) {
        var adminId = admin().getStaffId();
        var steps = List.of(FulfillmentStatus.IN_PRODUCTION, FulfillmentStatus.READY_TO_SHIP,
            FulfillmentStatus.SHIPPED, FulfillmentStatus.DELIVERED);

        for (var step : steps) {
            fulfillmentService.advance(adminId, itemId, FulfillmentChange.builder().withStatus(step).build());
        }

        var deliveredAt = now().minusHours(hoursAgo);
        jdbc.update("UPDATE dbo.PhysicalFulfillment SET DeliveredAt = ?, UpdatedAt = ? WHERE OrderItemId = ?",
            deliveredAt, deliveredAt, itemId);
    }

    private ReleaseOutcome paid(String transferReference) {
        return ReleaseOutcome.builder()
            .withStatus(ReleaseStatus.PAID)
            .withTransferReference(transferReference)
            .withReason("Transferred")
            .build();
    }

    // A published Listing of a new Artist Profile, with one protected file whose bytes exist.
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

    private long pendingOrder(String amount) {
        var orderId = jdbc.queryForObject("INSERT INTO dbo.MarketplaceOrder (CustomerId, Status, CreatedAt,"
                + " ExpiresAt, ItemsSubtotal) OUTPUT INSERTED.OrderId VALUES (?, 'PENDING_PAYMENT', ?, ?, ?)",
            Long.class, buyerId, now().minusMinutes(40), now().plusMinutes(30).plusSeconds(1),
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

    // Two prints: proceeds are quantity x markup = 60 000.
    private long physicalItem(long orderId, ListingAsset asset) {
        var itemId = jdbc.queryForObject("INSERT INTO dbo.OrderItem (OrderId, ListingId, ListingType, ArtworkId,"
                + " ArtistId, ArtworkTitle, ListingName, ArtistName, Specifications, Quantity, UnitPrice,"
                + " ManufacturingCostPerUnit, MarkupPerUnit, FeeRate, FeeAmount, ArtistProceeds)"
                + " OUTPUT INSERTED.OrderItemId VALUES (?, ?, 'PHYSICAL', ?, ?, N'Sunset', N'Sunset A4 print',"
                + " N'Painter', ?, 2, 110000, 80000, 30000, 0, 0, 60000)",
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

    private int entitlements(long orderItemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.DigitalEntitlement WHERE OrderItemId = ?",
            Integer.class, orderItemId);
    }

    private int proceedsCount(long orderItemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.ArtistProceeds WHERE OrderItemId = ?", Integer.class,
            orderItemId);
    }

    private long proceedsId(long orderItemId) {
        return jdbc.queryForObject("SELECT ProceedsId FROM dbo.ArtistProceeds WHERE OrderItemId = ?", Long.class,
            orderItemId);
    }

    private String proceedsStatus(long orderItemId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.ArtistProceeds WHERE OrderItemId = ?", String.class,
            orderItemId);
    }

    private BigDecimal proceedsAmount(long orderItemId) {
        return jdbc.queryForObject("SELECT Amount FROM dbo.ArtistProceeds WHERE OrderItemId = ?", BigDecimal.class,
            orderItemId);
    }

    private Optional<OffsetDateTime> eligibleAt(long orderItemId) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT EligibleAt FROM dbo.ArtistProceeds"
            + " WHERE OrderItemId = ?", OffsetDateTime.class, orderItemId));
    }

    private List<Long> releaseIds(long proceedsId) {
        return jdbc.queryForList("SELECT PayoutId FROM dbo.PayoutLine WHERE ProceedsId = ? ORDER BY PayoutId",
            Long.class, proceedsId);
    }

    private int activeLines(long proceedsId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.PayoutLine WHERE ProceedsId = ? AND IsActive = 1",
            Integer.class, proceedsId);
    }

    private List<BigDecimal> lineAmounts(long payoutId) {
        return jdbc.queryForList("SELECT Amount FROM dbo.PayoutLine WHERE PayoutId = ?", BigDecimal.class,
            payoutId);
    }

    private int artistReleases(long artistId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.Payout WHERE ArtistId = ?", Integer.class, artistId);
    }

    private String releaseStatus(long payoutId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.Payout WHERE PayoutId = ?", String.class, payoutId);
    }

    private BigDecimal releaseAmount(long payoutId) {
        return jdbc.queryForObject("SELECT Amount FROM dbo.Payout WHERE PayoutId = ?", BigDecimal.class, payoutId);
    }

    private long releaseArtist(long payoutId) {
        return jdbc.queryForObject("SELECT ArtistId FROM dbo.Payout WHERE PayoutId = ?", Long.class, payoutId);
    }

    private String releaseReference(long payoutId) {
        return jdbc.queryForObject("SELECT ProviderPayoutRef FROM dbo.Payout WHERE PayoutId = ?", String.class,
            payoutId);
    }

    private List<String> audits(long payoutId) {
        return jdbc.queryForList("SELECT ActionType FROM dbo.AdminAudit WHERE TargetType = 'PAYOUT'"
            + " AND TargetId = ? ORDER BY AuditId", String.class, payoutId);
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
        return staffRepository.saveAndFlush(new StaffAccount("f14-test-" + UUID.randomUUID(),
            "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now()));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
