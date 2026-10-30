package com.exe101.listing.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.artwork.repository.ArtworkRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.persistence.DatabaseErrors;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.repository.CustomerRepository;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.Artwork;
import com.exe101.entity.Listing;
import com.exe101.entity.ListingAsset;
import com.exe101.entity.StaffAccount;
import com.exe101.entity.StoredFile;
import com.exe101.file.repository.StoredFileRepository;
import com.exe101.listing.model.ListingDetails;
import com.exe101.listing.model.ListingType;
import com.exe101.listing.service.ListingService;
import com.exe101.staff.repository.StaffRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class ListingPersistenceTest {
    private static String a4Specifications = "Fine-art print, A4 (210 x 297 mm), 230 gsm matte paper";
    // THROW 51005 in TR_ListingAsset_Published.
    private static int publishedAssetsError = 51005;
    @Autowired
    private ListingService service;
    @Autowired
    private ListingRepository repository;
    @Autowired
    private ListingAssetRepository assetRepository;
    @Autowired
    private ArtworkRepository artworkRepository;
    @Autowired
    private StoredFileRepository fileRepository;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void physical_listing_price_persisted_exactly_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var userId = registerUser();
            var artwork = publishedArtwork(artistFor(userId, admin().getStaffId()).getArtistId());
            var created = service.create(userId, new ListingDetails(artwork.getArtworkId(), ListingType.PHYSICAL,
                "Sunset A4 print", null, null, "PRINT_A4", new BigDecimal("30000"), null));
            var row = jdbc.queryForMap("SELECT Price, ManufacturingCost, ArtistMarkup, Specifications, CurrencyCode"
                + " FROM dbo.Listing WHERE ListingId = ?", created.getListingId());
            var price = (BigDecimal) row.get("Price");
            assertAll(
                () -> assertThat(price).isEqualByComparingTo("110000"),
                () -> assertThat(price.scale()).isEqualTo(4),
                () -> assertThat((BigDecimal) row.get("ManufacturingCost")).isEqualByComparingTo("80000"),
                () -> assertThat((BigDecimal) row.get("ArtistMarkup")).isEqualByComparingTo("30000"),
                () -> assertThat(row.get("Specifications")).isEqualTo(a4Specifications),
                () -> assertThat(row.get("CurrencyCode")).isEqualTo("VND")
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void physical_listing_price_mismatch_rejected_by_database_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var userId = registerUser();
            var artwork = publishedArtwork(artistFor(userId, admin().getStaffId()).getArtistId());
            var mismatch = new Listing(artwork.getArtworkId(), "PHYSICAL", "Mismatch", null, "DRAFT", "VND",
                new BigDecimal("120000"), new BigDecimal("80000"), new BigDecimal("30000"), a4Specifications,
                false, now(), null, null);
            assertAll(
                () -> assertThatThrownBy(() -> repository.saveAndFlush(mismatch))
                    .isInstanceOf(DataIntegrityViolationException.class),
                () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.Listing WHERE ArtworkId = ?",
                    Integer.class, artwork.getArtworkId())).isZero()
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void published_listing_asset_change_rolls_back_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);
        var listingId = new AtomicLong();

        // when / then
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            var userId = registerUser();
            var artwork = publishedArtwork(artistFor(userId, admin().getStaffId()).getArtistId());
            var listing = repository.saveAndFlush(new Listing(artwork.getArtworkId(), "DIGITAL", "Sunset download",
                null, "PUBLISHED", "VND", new BigDecimal("50000"), null, null, null, false, now(), now(), null));
            var file = fileRepository.saveAndFlush(new StoredFile("f06-test/" + UUID.randomUUID(), "extra.png",
                "image/png", 12, "PURCHASED", now()));
            listingId.set(listing.getListingId());
            assetRepository.saveAndFlush(new ListingAsset(listing.getListingId(), file.getFileId(), 0, now()));
        }))
            .isInstanceOf(DataAccessException.class)
            .satisfies(exception -> assertThat(DatabaseErrors.hasErrorCode(exception, publishedAssetsError))
                .isTrue());
        assertAll(
            () -> assertThat(repository.findById(listingId.get())).isEmpty(),
            () -> assertThat(assetRepository.countByListingId(listingId.get())).isZero()
        );
    }

    @Test
    void concurrent_listing_update_detected_ko() {
        // given
        var existingAdmin = existingAdmin();
        var admin = existingAdmin.orElseGet(this::newAdmin);
        var userId = registerUser();
        var artist = artistFor(userId, admin.getStaffId());
        var artwork = publishedArtwork(artist.getArtistId());
        var listing = repository.saveAndFlush(new Listing(artwork.getArtworkId(), "DIGITAL", "Sunset download",
            null, "DRAFT", "VND", new BigDecimal("50000"), null, null, null, false, now(), null, null));

        // when / then
        try {
            var current = service.getOwn(userId, listing.getListingId());
            var first = service.update(userId, listing.getListingId(), new ListingDetails(null, null,
                "Sunset (4K)", null, new BigDecimal("60000"), null, null, current.getVersion()));
            assertAll(
                () -> assertThat(first.getVersion()).isNotEqualTo(current.getVersion()),
                () -> assertThatThrownBy(() -> service.update(userId, listing.getListingId(), new ListingDetails(
                    null, null, "Sunset (8K)", null, new BigDecimal("70000"), null, null, current.getVersion())))
                    .isInstanceOf(AppException.class)
                    .hasMessage(AppErrorMessage.LISTING_VERSION_CONFLICT.getDetail()),
                () -> assertThat(repository.findById(listing.getListingId()).orElseThrow().getPrice())
                    .isEqualByComparingTo("60000")
            );
        } finally {
            repository.deleteById(listing.getListingId());
            artworkRepository.deleteById(artwork.getArtworkId());
            artistRepository.deleteById(artist.getArtistId());
            customerRepository.deleteById(userId);

            if (existingAdmin.isEmpty()) {
                staffRepository.deleteById(admin.getStaffId());
            }
        }
    }

    private Long registerUser() {
        return customerService.register(new CustomerRegistration(
            "f06-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Painter")).getUserId();
    }

    private ArtistProfile artistFor(Long userId, Long staffId) {
        return artistRepository.saveAndFlush(new ArtistProfile(userId, "Painter", "ACTIVE", false, staffId, now(),
            null));
    }

    private Artwork publishedArtwork(long artistId) {
        return artworkRepository.saveAndFlush(new Artwork(artistId, "Sunset", null, "2D", "PUBLISHED", now(), now(),
            null));
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
        return staffRepository.saveAndFlush(new StaffAccount("f06-test-" + UUID.randomUUID(),
            "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now()));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
