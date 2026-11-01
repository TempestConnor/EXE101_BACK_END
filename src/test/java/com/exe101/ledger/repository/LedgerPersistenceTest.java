package com.exe101.ledger.repository;

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
import com.exe101.ledger.model.Mismatch;
import com.exe101.ledger.service.LedgerService;
import com.exe101.listing.repository.ListingAssetRepository;
import com.exe101.listing.repository.ListingRepository;
import com.exe101.payment.config.VnpayProperties;
import com.exe101.payment.model.EventOutcome;
import com.exe101.payment.model.NotificationResult;
import com.exe101.payment.model.VnpayReply;
import com.exe101.payment.service.PaymentService;
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
import org.springframework.dao.DataAccessException;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

// The ledger follows committed payments, and committed Orders and ledger rows can never be deleted (immutability
// triggers). So this runs only against the disposable test database (database/test/reset_test_database.sql),
// selected by TEST_DB_URL, and leaves its rows there. Each test uses a new buyer, sellers and Order; bytes written to
// storage are deleted afterwards. The scheduled jobs are slowed down so only the tests run them.
@SpringBootTest(properties = {"app.orders.expiry-interval=PT1H", "app.fulfillment.grant-interval=PT1H"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class LedgerPersistenceTest {
    private static String a4Specifications = "Fine-art print, A4 (210 x 297 mm), 230 gsm matte paper";
    private static byte[] fileBytes = "purchased-file".getBytes(StandardCharsets.UTF_8);
    private static long blockedMillis = 500;
    private static long waitSeconds = 30;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private ProceedsService proceedsService;
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
    private LedgerEntryRepository ledgerEntryRepository;
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
        reset(ledgerEntryRepository);

        for (var storageKey : storageKeys) {
            Files.deleteIfExists(Path.of(storage.root()).resolve(storageKey));
        }
    }

    @Test
    void duplicate_ipn_records_payment_once_ok() {
        // given
        var digital = listingAsset("DIGITAL");
        var orderId = pendingOrder("50000");
        digitalItem(orderId, digital);
        var success = success(attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId()),
            "5000000");

        // when
        var first = paymentService.handleVnpayNotification(success);
        var repeated = paymentService.handleVnpayNotification(success);

        // then
        var paymentId = paymentIdOf(orderId);
        assertAll(
            () -> assertThat(first.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(repeated.isDuplicate()).isTrue(),
            () -> assertThat(transactions("PAYMENT_RECEIVED", "PAYMENT", paymentId)).hasSize(1),
            () -> assertThat(cashOf(transactions("PAYMENT_RECEIVED", "PAYMENT", paymentId)))
                .isEqualByComparingTo("50000")
        );
    }

    @Test
    void concurrent_ipn_records_payment_once_ok() throws Exception {
        // given
        // Two attempts of one Order both report success; the second waits, then is money that applies to nothing.
        var digital = listingAsset("DIGITAL");
        var orderId = pendingOrder("50000");
        digitalItem(orderId, digital);
        var firstRef = attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId());
        var secondRef = attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId());
        var handled = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            Future<NotificationResult> first = executor.submit(() -> inTransaction(() -> {
                var outcome = paymentService.handleVnpayNotification(success(firstRef, "5000000"));
                handled.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return outcome;
            }));
            handled.await(waitSeconds, TimeUnit.SECONDS);
            Future<NotificationResult> second = executor.submit(() ->
                paymentService.handleVnpayNotification(success(secondRef, "5000000")));
            var secondWaited = isBlocked(second);
            release.countDown();
            var firstResult = first.get(waitSeconds, TimeUnit.SECONDS);
            var secondResult = second.get(waitSeconds, TimeUnit.SECONDS);

            // then
            var paymentId = paymentIdOf(orderId);
            var unapplied = transactions("PAYMENT_UNAPPLIED", "PROVIDER_EVENT", providerEventOf(secondRef));
            assertAll(
                () -> assertThat(secondWaited).isTrue(),
                () -> assertThat(firstResult.getReply()).isEqualTo(VnpayReply.CONFIRMED),
                () -> assertThat(secondResult.getOutcome()).isEqualTo(EventOutcome.EXCEPTION),
                () -> assertThat(transactions("PAYMENT_RECEIVED", "PAYMENT", paymentId)).hasSize(1),
                () -> assertThat(unapplied).hasSize(1),
                () -> assertThat(accountOf(unapplied, "UNAPPLIED")).isEqualByComparingTo("-50000")
            );
        }
    }

    @Test
    void exception_payment_records_unapplied_ok() {
        // given
        // The buyer pays 15 000 for a 50 000 Order: the money arrived, but it pays for nothing.
        var digital = listingAsset("DIGITAL");
        var orderId = pendingOrder("50000");
        var itemId = digitalItem(orderId, digital);
        var ref = attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId());

        // when
        var result = paymentService.handleVnpayNotification(success(ref, "1500000"));

        // then
        var unapplied = transactions("PAYMENT_UNAPPLIED", "PROVIDER_EVENT", providerEventOf(ref));
        assertAll(
            () -> assertThat(result.getReply()).isEqualTo(VnpayReply.INVALID_AMOUNT),
            () -> assertThat(unapplied).hasSize(1),
            () -> assertThat(accountOf(unapplied, "CASH")).isEqualByComparingTo("15000"),
            () -> assertThat(accountOf(unapplied, "UNAPPLIED")).isEqualByComparingTo("-15000"),
            () -> assertThat(transactions("PAYMENT_RECEIVED", "PAYMENT", paymentIdOf(orderId))).isEmpty(),
            () -> assertThat(itemEntries(itemId)).isZero()
        );
    }

    @Test
    void duplicate_event_rejected_by_unique_key_ko() {
        // given
        // The same event recorded twice in one transaction: the unique key refuses the second, and both roll back.
        var sourceId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);

        // when / then
        assertThatThrownBy(() -> inTransaction(() -> {
            ledgerService.recordUnapplied(sourceId, new BigDecimal("1000"), now());
            ledgerService.recordUnapplied(sourceId, new BigDecimal("1000"), now());
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(transactions("PAYMENT_UNAPPLIED", "PROVIDER_EVENT", sourceId)).isEmpty();
    }

    @Test
    void ledger_entries_immutable_ko() {
        // given
        var orderId = paidDigitalOrder();
        var transactionId = transactions("PAYMENT_RECEIVED", "PAYMENT", paymentIdOf(orderId)).getFirst();
        var entriesBefore = entries(transactionId);

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> jdbc.update("UPDATE dbo.LedgerEntry SET Amount = Amount + 1"
                + " WHERE TransactionId = ?", transactionId)).isInstanceOf(DataAccessException.class),
            () -> assertThatThrownBy(() -> jdbc.update("DELETE FROM dbo.LedgerEntry WHERE TransactionId = ?",
                transactionId)).isInstanceOf(DataAccessException.class),
            () -> assertThatThrownBy(() -> jdbc.update("UPDATE dbo.LedgerTransaction SET SourceId = SourceId + 1"
                + " WHERE TransactionId = ?", transactionId)).isInstanceOf(DataAccessException.class),
            () -> assertThatThrownBy(() -> jdbc.update("DELETE FROM dbo.LedgerTransaction WHERE TransactionId = ?",
                transactionId)).isInstanceOf(DataAccessException.class),
            () -> assertThat(entries(transactionId)).isEqualTo(entriesBefore)
        );
    }

    @Test
    void ledger_failure_rolls_back_payment_ko() {
        // given
        var digital = listingAsset("DIGITAL");
        var orderId = pendingOrder("50000");
        var itemId = digitalItem(orderId, digital);
        var success = success(attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId()),
            "5000000");
        doThrow(new IllegalStateException("ledger entry insert failed")).when(ledgerEntryRepository)
            .saveAllAndFlush(anyList());

        // when
        assertThatThrownBy(() -> paymentService.handleVnpayNotification(success))
            .isInstanceOf(IllegalStateException.class);
        var orderAfterFailure = orderStatus(orderId);
        var receiptsAfterFailure = transactions("PAYMENT_RECEIVED", "PAYMENT", paymentIdOf(orderId));
        var proceedsAfterFailure = proceedsCount(itemId);
        var entitlementsAfterFailure = entitlements(itemId);
        reset(ledgerEntryRepository);
        var retried = paymentService.handleVnpayNotification(success);

        // then
        assertAll(
            () -> assertThat(orderAfterFailure).isEqualTo("PENDING_PAYMENT"),
            () -> assertThat(receiptsAfterFailure).isEmpty(),
            () -> assertThat(proceedsAfterFailure).isZero(),
            () -> assertThat(entitlementsAfterFailure).isZero(),
            () -> assertThat(retried.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(transactions("PAYMENT_RECEIVED", "PAYMENT", paymentIdOf(orderId))).hasSize(1)
        );
    }

    @Test
    void full_flow_reconciles_ok() {
        // given
        // A digital item (50 000) and two prints (2 x (80 000 + 30 000)) in one Order of 270 000.
        var digital = listingAsset("DIGITAL");
        var physical = listingAsset("PHYSICAL");
        var orderId = pendingOrder("270000");
        var digitalItemId = digitalItem(orderId, digital);
        var physicalItemId = physicalItem(orderId, physical);
        var ref = attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId());
        var adminId = admin().getStaffId();

        // when
        paymentService.handleVnpayNotification(success(ref, "27000000"));
        deliver(physicalItemId, 49);
        returnWindowJob.grantClosedWindows();
        var digitalRelease = releaseOf(digitalItemId);
        var physicalRelease = releaseOf(physicalItemId);
        proceedsService.recordOutcome(adminId, digitalRelease, paid());
        proceedsService.recordOutcome(adminId, physicalRelease, paid());
        var reconciliation = ledgerService.reconcile();

        // then
        var paymentId = paymentIdOf(orderId);
        var flow = new ArrayList<Long>(transactions("PAYMENT_RECEIVED", "PAYMENT", paymentId));
        flow.addAll(transactions("RELEASE_PAID", "PAYOUT", digitalRelease));
        flow.addAll(transactions("RELEASE_PAID", "PAYOUT", physicalRelease));
        var ownRecords = List.of(paymentId, digitalRelease, physicalRelease, digitalItemId, physicalItemId,
            proceedsId(digitalItemId), proceedsId(physicalItemId));
        assertAll(
            () -> assertThat(reconciliation.getMismatches()).extracting(Mismatch::getRecordId)
                .doesNotContainAnyElementsOf(ownRecords),
            () -> assertThat(proceedsStatus(digitalItemId)).isEqualTo("PAID"),
            () -> assertThat(proceedsStatus(physicalItemId)).isEqualTo("PAID"),
            () -> assertThat(artistBalance(digitalItemId)).isEqualByComparingTo("0"),
            () -> assertThat(artistBalance(physicalItemId)).isEqualByComparingTo("0"),
            () -> assertThat(cashOf(flow)).isEqualByComparingTo("160000"),
            () -> assertThat(itemAccount(physicalItemId, "MANUFACTURER_PAYABLE")).isEqualByComparingTo("-160000")
        );
    }

    private Long registerUser() {
        return customerService.register(new CustomerRegistration(
            "f14-ledger-" + UUID.randomUUID() + "@example.invalid", "long-password", "Buyer")).getUserId();
    }

    private long paidDigitalOrder() {
        var digital = listingAsset("DIGITAL");
        var orderId = pendingOrder("50000");
        digitalItem(orderId, digital);
        var ref = attemptRef(paymentService.start(buyerId, orderId, "203.0.113.7").getAttemptId());
        paymentService.handleVnpayNotification(success(ref, "5000000"));

        return orderId;
    }

    private ReleaseOutcome paid() {
        return ReleaseOutcome.builder()
            .withStatus(ReleaseStatus.PAID)
            .withTransferReference("test-" + UUID.randomUUID())
            .withReason("Transferred")
            .build();
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

    // Two prints: proceeds are quantity x markup = 60 000, manufacturing quantity x cost = 160 000.
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

    private long paymentIdOf(long orderId) {
        return jdbc.queryForObject("SELECT PaymentId FROM dbo.Payment WHERE OrderId = ?", Long.class, orderId);
    }

    private long providerEventOf(String ref) {
        return jdbc.queryForObject("SELECT e.ProviderEventId FROM dbo.ProviderEvent e JOIN dbo.PaymentAttempt a"
            + " ON a.AttemptId = e.AttemptId WHERE a.ProviderAttemptRef = ?", Long.class, ref);
    }

    private long releaseOf(long orderItemId) {
        return jdbc.queryForObject("SELECT l.PayoutId FROM dbo.PayoutLine l JOIN dbo.ArtistProceeds p"
            + " ON p.ProceedsId = l.ProceedsId WHERE p.OrderItemId = ? AND l.IsActive = 1", Long.class, orderItemId);
    }

    private long proceedsId(long orderItemId) {
        return jdbc.queryForObject("SELECT ProceedsId FROM dbo.ArtistProceeds WHERE OrderItemId = ?", Long.class,
            orderItemId);
    }

    private String proceedsStatus(long orderItemId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.ArtistProceeds WHERE OrderItemId = ?", String.class,
            orderItemId);
    }

    private int proceedsCount(long orderItemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.ArtistProceeds WHERE OrderItemId = ?", Integer.class,
            orderItemId);
    }

    private int entitlements(long orderItemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.DigitalEntitlement WHERE OrderItemId = ?",
            Integer.class, orderItemId);
    }

    private List<Long> transactions(String type, String sourceType, long sourceId) {
        return jdbc.queryForList("SELECT TransactionId FROM dbo.LedgerTransaction WHERE Type = ? AND SourceType = ?"
            + " AND SourceId = ? ORDER BY TransactionId", Long.class, type, sourceType, sourceId);
    }

    private List<Map<String, Object>> entries(long transactionId) {
        return jdbc.queryForList("SELECT EntryId, Account, Amount FROM dbo.LedgerEntry WHERE TransactionId = ?"
            + " ORDER BY EntryId", transactionId);
    }

    private BigDecimal cashOf(List<Long> transactionIds) {
        return transactionIds.stream()
            .map(transactionId -> accountOf(List.of(transactionId), "CASH"))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal accountOf(List<Long> transactionIds, String account) {
        return transactionIds.stream()
            .map(transactionId -> jdbc.queryForObject("SELECT COALESCE(SUM(Amount), 0) FROM dbo.LedgerEntry"
                + " WHERE TransactionId = ? AND Account = ?", BigDecimal.class, transactionId, account))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private int itemEntries(long orderItemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.LedgerEntry WHERE OrderItemId = ?", Integer.class,
            orderItemId);
    }

    private BigDecimal itemAccount(long orderItemId, String account) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(Amount), 0) FROM dbo.LedgerEntry WHERE OrderItemId = ?"
            + " AND Account = ?", BigDecimal.class, orderItemId, account);
    }

    // Everything owed to the Artist for the item, across held, available and releasing.
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

    private StaffAccount admin() {
        return existingAdmin().orElseGet(this::newAdmin);
    }

    private Optional<StaffAccount> existingAdmin() {
        return staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive())
            .findFirst();
    }

    private StaffAccount newAdmin() {
        return staffRepository.saveAndFlush(new StaffAccount("f14-ledger-" + UUID.randomUUID(),
            "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now()));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
