package com.exe101.file.repository;

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
import com.exe101.entity.StoredFile;
import com.exe101.file.config.StorageProperties;
import com.exe101.file.model.NewFile;
import com.exe101.file.service.FileService;
import com.exe101.listing.repository.ListingAssetRepository;
import com.exe101.listing.repository.ListingRepository;
import com.exe101.staff.repository.StaffRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

// Order, Order Item, entitlement and file rows are immutable (database triggers), so every test rolls back; bytes
// written to storage are deleted afterwards.
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class PurchasedFilePersistenceTest {
    private static String a4Specifications = "Fine-art print, A4 (210 x 297 mm), 230 gsm matte paper";
    private static byte[] fileBytes = "purchased-file".getBytes(StandardCharsets.UTF_8);
    @Autowired
    private PurchasedFileRepository repository;
    @Autowired
    private FileService fileService;
    @Autowired
    private StoredFileRepository fileRepository;
    @Autowired
    private ListingRepository listingRepository;
    @Autowired
    private ListingAssetRepository assetRepository;
    @Autowired
    private ArtworkRepository artworkRepository;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private StorageProperties storage;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void entitled_file_resolves_exact_recorded_file_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var buyerId = registerUser();
            var listing = draftListing("DIGITAL");
            var file = storedFile("f08-test/" + UUID.randomUUID());
            var asset = asset(listing, file, 0);
            publish(listing);
            var orderItemId = digitalOrderItem(buyerId, listing);
            recordAsset(orderItemId, asset);
            grant(buyerId, orderItemId, asset);
            assertThat(repository.findEntitledFileId(buyerId, asset.getListingAssetId()))
                .contains(file.getFileId());
            status.setRollbackOnly();
        });
    }

    @Test
    void entitled_file_other_customer_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var buyerId = registerUser();
            var otherId = registerUser();
            var listing = draftListing("DIGITAL");
            var asset = asset(listing, storedFile("f08-test/" + UUID.randomUUID()), 0);
            publish(listing);
            var orderItemId = digitalOrderItem(buyerId, listing);
            recordAsset(orderItemId, asset);
            grant(buyerId, orderItemId, asset);
            assertThat(repository.findEntitledFileId(otherId, asset.getListingAssetId())).isEmpty();
            status.setRollbackOnly();
        });
    }

    @Test
    void download_other_customers_entitlement_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var buyerId = registerUser();
            var otherId = registerUser();
            var listing = draftListing("DIGITAL");
            var asset = asset(listing, storedFile("f08-test/" + UUID.randomUUID()), 0);
            publish(listing);
            var orderItemId = digitalOrderItem(buyerId, listing);
            recordAsset(orderItemId, asset);
            grant(buyerId, orderItemId, asset);
            assertThatThrownBy(() -> fileService.readPurchased(otherId, asset.getListingAssetId()))
                .isInstanceOf(AppException.class)
                .extracting("error")
                .isEqualTo(AppErrorMessage.ENTITLEMENT_REQUIRED);
            status.setRollbackOnly();
        });
    }

    @Test
    void download_physical_item_without_entitlement_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var buyerId = registerUser();
            var listing = draftListing("PHYSICAL");
            var asset = asset(listing, storedFile("f08-test/" + UUID.randomUUID()), 0);
            publish(listing);
            var orderItemId = physicalOrderItem(buyerId, listing);
            recordAsset(orderItemId, asset);
            assertThatThrownBy(() -> fileService.readPurchased(buyerId, asset.getListingAssetId()))
                .isInstanceOf(AppException.class)
                .extracting("error")
                .isEqualTo(AppErrorMessage.ENTITLEMENT_REQUIRED);
            status.setRollbackOnly();
        });
    }

    @Test
    void download_asset_not_in_entitlement_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var buyerId = registerUser();
            var listing = draftListing("DIGITAL");
            var granted = asset(listing, storedFile("f08-test/" + UUID.randomUUID()), 0);
            var notGranted = asset(listing, storedFile("f08-test/" + UUID.randomUUID()), 1);
            publish(listing);
            var orderItemId = digitalOrderItem(buyerId, listing);
            recordAsset(orderItemId, granted);
            recordAsset(orderItemId, notGranted);
            grant(buyerId, orderItemId, granted);
            assertAll(
                () -> assertThat(repository.findEntitledFileId(buyerId, granted.getListingAssetId())).isPresent(),
                () -> assertThatThrownBy(() -> fileService.readPurchased(buyerId, notGranted.getListingAssetId()))
                    .isInstanceOf(AppException.class)
                    .extracting("error")
                    .isEqualTo(AppErrorMessage.ENTITLEMENT_REQUIRED)
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void download_after_listing_unlisted_ok() throws Exception {
        // given
        var transaction = new TransactionTemplate(transactionManager);
        var storageKey = new AtomicReference<String>();

        try {
            // when / then
            transaction.executeWithoutResult(status -> {
                var stored = fileService.storeProtected(new NewFile("sunset.png", "image/png", fileBytes));
                storageKey.set(stored.getStorageKey());
                var buyerId = registerUser();
                var listing = draftListing("DIGITAL");
                var asset = asset(listing, fileRepository.findById(stored.getFileId()).orElseThrow(), 0);
                publish(listing);
                var orderItemId = digitalOrderItem(buyerId, listing);
                recordAsset(orderItemId, asset);
                grant(buyerId, orderItemId, asset);
                setStatus(listing, "UNLISTED");
                var content = fileService.readPurchased(buyerId, asset.getListingAssetId());
                assertAll(
                    () -> assertThat(content.getFileId()).isEqualTo(stored.getFileId()),
                    () -> assertThat(content.getContent().getContentAsByteArray()).isEqualTo(fileBytes)
                );
                status.setRollbackOnly();
            });
        } finally {
            deleteBytes(storageKey.get());
        }
    }

    @Test
    void download_after_listing_moderated_ok() throws Exception {
        // given
        var transaction = new TransactionTemplate(transactionManager);
        var storageKey = new AtomicReference<String>();

        try {
            // when / then
            transaction.executeWithoutResult(status -> {
                var stored = fileService.storeProtected(new NewFile("sunset.png", "image/png", fileBytes));
                storageKey.set(stored.getStorageKey());
                var buyerId = registerUser();
                var listing = draftListing("DIGITAL");
                var asset = asset(listing, fileRepository.findById(stored.getFileId()).orElseThrow(), 0);
                publish(listing);
                var orderItemId = digitalOrderItem(buyerId, listing);
                recordAsset(orderItemId, asset);
                grant(buyerId, orderItemId, asset);
                setStatus(listing, "MODERATED");
                var content = fileService.readPurchased(buyerId, asset.getListingAssetId());
                assertThat(content.getFileId()).isEqualTo(stored.getFileId());
                status.setRollbackOnly();
            });
        } finally {
            deleteBytes(storageKey.get());
        }
    }

    private void deleteBytes(String storageKey) throws IOException {
        if (storageKey != null) {
            Files.deleteIfExists(Path.of(storage.root()).resolve(storageKey));
        }
    }

    private Long registerUser() {
        return customerService.register(new CustomerRegistration(
            "f08-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Buyer")).getUserId();
    }

    private Listing draftListing(String listingType) {
        var sellerId = registerUser();
        var artist = artistRepository.saveAndFlush(new ArtistProfile(sellerId, "Painter", "ACTIVE", false,
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

    private StoredFile storedFile(String storageKey) {
        return fileRepository.saveAndFlush(new StoredFile(storageKey, "sunset.png", "image/png", 12, "PURCHASED",
            now()));
    }

    private ListingAsset asset(Listing listing, StoredFile file, int sortOrder) {
        return assetRepository.saveAndFlush(new ListingAsset(listing.getListingId(), file.getFileId(), sortOrder,
            now()));
    }

    private void publish(Listing listing) {
        jdbc.update("UPDATE dbo.Listing SET Status = 'PUBLISHED', PublishedAt = ? WHERE ListingId = ?", now(),
            listing.getListingId());
    }

    private void setStatus(Listing listing, String status) {
        jdbc.update("UPDATE dbo.Listing SET Status = ? WHERE ListingId = ?", status, listing.getListingId());
    }

    private long digitalOrderItem(long buyerId, Listing listing) {
        return jdbc.queryForObject("INSERT INTO dbo.OrderItem (OrderId, ListingId, ListingType, ArtworkId, ArtistId,"
                + " ArtworkTitle, ListingName, ArtistName, Quantity, UnitPrice, FeeRate, FeeAmount, ArtistProceeds)"
                + " OUTPUT INSERTED.OrderItemId VALUES (?, ?, 'DIGITAL', ?, ?, N'Sunset', N'Sunset download',"
                + " N'Painter', 1, 50000, 0, 0, 50000)",
            Long.class, paidOrder(buyerId, "50000"), listing.getListingId(), listing.getArtworkId(),
            artistOf(listing));
    }

    private long physicalOrderItem(long buyerId, Listing listing) {
        return jdbc.queryForObject("INSERT INTO dbo.OrderItem (OrderId, ListingId, ListingType, ArtworkId, ArtistId,"
                + " ArtworkTitle, ListingName, ArtistName, Specifications, Quantity, UnitPrice,"
                + " ManufacturingCostPerUnit, MarkupPerUnit, FeeRate, FeeAmount, ArtistProceeds)"
                + " OUTPUT INSERTED.OrderItemId VALUES (?, ?, 'PHYSICAL', ?, ?, N'Sunset', N'Sunset A4 print',"
                + " N'Painter', ?, 1, 110000, 80000, 30000, 0, 0, 30000)",
            Long.class, paidOrder(buyerId, "110000"), listing.getListingId(), listing.getArtworkId(),
            artistOf(listing), a4Specifications);
    }

    private long paidOrder(long buyerId, String itemsSubtotal) {
        return jdbc.queryForObject("INSERT INTO dbo.MarketplaceOrder (CustomerId, Status, ExpiresAt, PaidAt,"
                + " ItemsSubtotal) OUTPUT INSERTED.OrderId VALUES (?, 'PAID',"
                + " DATEADD(HOUR, 1, TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00')),"
                + " TODATETIMEOFFSET(SYSUTCDATETIME(), '+00:00'), ?)",
            Long.class, buyerId, new BigDecimal(itemsSubtotal));
    }

    private long artistOf(Listing listing) {
        return artworkRepository.findById(listing.getArtworkId()).orElseThrow().getArtistId();
    }

    private void recordAsset(long orderItemId, ListingAsset asset) {
        jdbc.update("INSERT INTO dbo.OrderItemAsset (OrderItemId, ListingId, ListingAssetId, FileId)"
            + " VALUES (?, ?, ?, ?)", orderItemId, asset.getListingId(), asset.getListingAssetId(), asset.getFileId());
    }

    private void grant(long buyerId, long orderItemId, ListingAsset asset) {
        var entitlementId = jdbc.queryForObject("INSERT INTO dbo.DigitalEntitlement (OrderItemId, CustomerId)"
            + " OUTPUT INSERTED.EntitlementId VALUES (?, ?)", Long.class, orderItemId, buyerId);
        jdbc.update("INSERT INTO dbo.EntitlementAsset (EntitlementId, ListingAssetId, OrderItemId) VALUES (?, ?, ?)",
            entitlementId, asset.getListingAssetId(), orderItemId);
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
        return staffRepository.saveAndFlush(new StaffAccount("f08-test-" + UUID.randomUUID(),
            "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now()));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
