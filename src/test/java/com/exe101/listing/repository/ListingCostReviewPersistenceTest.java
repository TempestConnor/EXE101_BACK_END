package com.exe101.listing.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.artwork.repository.ArtworkRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.Artwork;
import com.exe101.entity.Listing;
import com.exe101.entity.StaffAccount;
import com.exe101.listing.model.CostReviewConfirmation;
import com.exe101.listing.service.ListingService;
import com.exe101.staff.repository.StaffRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

// Every test runs in one transaction that is rolled back: OrderItem rows cannot be deleted
// (TR_OrderItem_Immutable), and the cost check scans every physical Listing in the database.
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class ListingCostReviewPersistenceTest {
    // The configured A4 cost is 80000; these Listings were priced at another cost.
    private static String a4Specifications = "Fine-art print, A4 (210 x 297 mm), 230 gsm matte paper";
    private static List<String> reviewableStatuses = List.of("DRAFT", "PUBLISHED", "UNLISTED");
    @Autowired
    private ListingService service;
    @Autowired
    private ListingRepository repository;
    @Autowired
    private ArtworkRepository artworkRepository;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void flag_cost_changes_query_flags_only_matching_listings_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var seller = seller();
            var stale = physical(seller.artworkId(), "PUBLISHED", "60000", false, a4Specifications);
            var current = physical(seller.artworkId(), "PUBLISHED", "80000", false, a4Specifications);
            var archived = physical(seller.artworkId(), "ARCHIVED", "60000", false, a4Specifications);
            var otherProduct = physical(seller.artworkId(), "PUBLISHED", "60000", false, "Hand-bound sketchbook");
            var digital = digital(seller.artworkId());
            var count = repository.flagCostChanges(a4Specifications, new BigDecimal("80000.0000"),
                reviewableStatuses);
            assertAll(
                () -> assertThat(count).isGreaterThanOrEqualTo(1),
                () -> assertThat(flag(stale)).isTrue(),
                () -> assertThat(flag(current)).isFalse(),
                () -> assertThat(flag(archived)).isFalse(),
                () -> assertThat(flag(otherProduct)).isFalse(),
                () -> assertThat(flag(digital)).isFalse()
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void cost_change_flags_draft_and_unlisted_listings_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var seller = seller();
            var draft = physical(seller.artworkId(), "DRAFT", "60000", false, a4Specifications);
            var unlisted = physical(seller.artworkId(), "UNLISTED", "60000", false, a4Specifications);
            service.flagCostChanges();
            assertAll(
                () -> assertThat(flag(draft)).isTrue(),
                () -> assertThat(flag(unlisted)).isTrue()
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void cost_change_ignores_moderated_and_archived_listings_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var seller = seller();
            var moderated = physical(seller.artworkId(), "MODERATED", "60000", false, a4Specifications);
            var archived = physical(seller.artworkId(), "ARCHIVED", "60000", false, a4Specifications);
            service.flagCostChanges();
            assertAll(
                () -> assertThat(flag(moderated)).isFalse(),
                () -> assertThat(flag(archived)).isFalse()
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void cost_change_ignores_other_products_and_digital_listings_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var seller = seller();
            var otherProduct = physical(seller.artworkId(), "PUBLISHED", "60000", false, "Hand-bound sketchbook");
            var digital = digital(seller.artworkId());
            service.flagCostChanges();
            assertAll(
                () -> assertThat(flag(otherProduct)).isFalse(),
                () -> assertThat(flag(digital)).isFalse()
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void cost_decrease_flags_listing_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var seller = seller();
            var listing = physical(seller.artworkId(), "PUBLISHED", "100000", false, a4Specifications);
            service.flagCostChanges();
            assertThat(flag(listing)).isTrue();
            status.setRollbackOnly();
        });
    }

    @Test
    void unchanged_cost_does_not_flag_listing_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var seller = seller();
            var listing = physical(seller.artworkId(), "PUBLISHED", "80000", false, a4Specifications);
            var before = rowVersion(listing);
            service.flagCostChanges();
            service.flagCostChanges();
            assertAll(
                () -> assertThat(flag(listing)).isFalse(),
                () -> assertThat(rowVersion(listing)).isEqualTo(before)
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void cost_reverted_before_review_keeps_flag_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var seller = seller();
            var listing = physical(seller.artworkId(), "PUBLISHED", "80000", true, a4Specifications);
            service.flagCostChanges();
            assertThat(flag(listing)).isTrue();
            status.setRollbackOnly();
        });
    }

    @Test
    void cost_change_persists_flag_and_keeps_price_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var seller = seller();
            var listing = physical(seller.artworkId(), "PUBLISHED", "60000", false, a4Specifications);
            service.flagCostChanges();
            var row = listingRow(listing);
            assertAll(
                () -> assertThat(row.get("CostReviewRequired")).isEqualTo(true),
                () -> assertThat((BigDecimal) row.get("Price")).isEqualByComparingTo("90000"),
                () -> assertThat((BigDecimal) row.get("ManufacturingCost")).isEqualByComparingTo("60000"),
                () -> assertThat((BigDecimal) row.get("ArtistMarkup")).isEqualByComparingTo("30000"),
                () -> assertThat(service.getOwn(seller.userId(), listing).isPurchasable()).isFalse()
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void confirm_review_persists_recomputed_price_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var seller = seller();
            var listing = physical(seller.artworkId(), "PUBLISHED", "60000", false, a4Specifications);
            service.flagCostChanges();
            var flagged = service.getOwn(seller.userId(), listing);
            var confirmed = service.confirmCostReview(seller.userId(), listing,
                new CostReviewConfirmation(new BigDecimal("30000"), flagged.getVersion()));
            var row = listingRow(listing);
            var price = (BigDecimal) row.get("Price");
            assertAll(
                () -> assertThat(flagged.getCurrentManufacturingCost()).isEqualByComparingTo("80000"),
                () -> assertThat(price).isEqualByComparingTo("110000"),
                () -> assertThat(price.scale()).isEqualTo(4),
                () -> assertThat((BigDecimal) row.get("ManufacturingCost")).isEqualByComparingTo("80000"),
                () -> assertThat((BigDecimal) row.get("ArtistMarkup")).isEqualByComparingTo("30000"),
                () -> assertThat(row.get("CostReviewRequired")).isEqualTo(false),
                () -> assertThat(confirmed.isPurchasable()).isTrue()
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void cost_change_leaves_existing_order_item_unchanged_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var seller = seller();
            var buyerId = registerUser();
            var listing = physical(seller.artworkId(), "PUBLISHED", "60000", false, a4Specifications);
            var orderItemId = orderItem(buyerId, seller, listing);
            var before = orderItemRow(orderItemId);
            service.flagCostChanges();
            var flagged = service.getOwn(seller.userId(), listing);
            service.confirmCostReview(seller.userId(), listing,
                new CostReviewConfirmation(new BigDecimal("50000"), flagged.getVersion()));
            var after = orderItemRow(orderItemId);
            assertAll(
                () -> assertThat(after).isEqualTo(before),
                () -> assertThat((BigDecimal) after.get("UnitPrice")).isEqualByComparingTo("90000"),
                () -> assertThat((BigDecimal) after.get("ManufacturingCostPerUnit")).isEqualByComparingTo("60000"),
                () -> assertThat((BigDecimal) after.get("MarkupPerUnit")).isEqualByComparingTo("30000"),
                () -> assertThat((BigDecimal) after.get("FeeRate")).isEqualByComparingTo("0"),
                () -> assertThat((BigDecimal) after.get("FeeAmount")).isEqualByComparingTo("0"),
                () -> assertThat((BigDecimal) after.get("ArtistProceeds")).isEqualByComparingTo("60000"),
                () -> assertThat((BigDecimal) listingRow(listing).get("Price")).isEqualByComparingTo("130000")
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void cost_change_during_review_confirmation_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var seller = seller();
            var listing = physical(seller.artworkId(), "PUBLISHED", "60000", false, a4Specifications);
            var staleRead = service.getOwn(seller.userId(), listing);
            service.flagCostChanges();
            assertAll(
                () -> assertThatThrownBy(() -> service.confirmCostReview(seller.userId(), listing,
                    new CostReviewConfirmation(new BigDecimal("30000"), staleRead.getVersion())))
                    .isInstanceOf(AppException.class)
                    .hasMessage(AppErrorMessage.LISTING_VERSION_CONFLICT.getDetail()),
                () -> assertThat(flag(listing)).isTrue(),
                () -> assertThat((BigDecimal) listingRow(listing).get("Price")).isEqualByComparingTo("90000")
            );
            status.setRollbackOnly();
        });
    }

    private Seller seller() {
        var userId = registerUser();
        var artist = artistRepository.saveAndFlush(new ArtistProfile(userId, "Painter", "ACTIVE", false,
            admin().getStaffId(), now(), null));
        var artwork = artworkRepository.saveAndFlush(new Artwork(artist.getArtistId(), "Sunset", null, "2D",
            "PUBLISHED", now(), now(), null));

        return new Seller(userId, artist.getArtistId(), artwork.getArtworkId());
    }

    // Markup 30000; retail is the given cost plus markup, as the database requires.
    private Long physical(long artworkId, String status, String cost, boolean flagged, String specifications) {
        var manufacturingCost = new BigDecimal(cost);
        var markup = new BigDecimal("30000");
        var publishedAt = "DRAFT".equals(status) ? null : now();

        return repository.saveAndFlush(new Listing(artworkId, "PHYSICAL", "Sunset A4 print", null, status, "VND",
            manufacturingCost.add(markup), manufacturingCost, markup, specifications, flagged, now(), publishedAt,
            null)).getListingId();
    }

    private Long digital(long artworkId) {
        return repository.saveAndFlush(new Listing(artworkId, "DIGITAL", "Sunset download", null, "PUBLISHED", "VND",
            new BigDecimal("50000"), null, null, null, false, now(), now(), null)).getListingId();
    }

    // A snapshot as F10 will write it: zero fee, proceeds = quantity x markup.
    private Long orderItem(Long buyerId, Seller seller, Long listingId) {
        var orderId = jdbc.queryForObject("INSERT INTO dbo.MarketplaceOrder (CustomerId, Status, ExpiresAt,"
            + " CurrencyCode, ItemsSubtotal, ShippingAmount, TaxAmount) OUTPUT INSERTED.OrderId"
            + " VALUES (?, 'PENDING_PAYMENT', DATEADD(HOUR, 1, SYSDATETIMEOFFSET()), 'VND', 180000, 0, 0)",
            Long.class, buyerId);

        return jdbc.queryForObject("INSERT INTO dbo.OrderItem (OrderId, ListingId, ListingType, ArtworkId, ArtistId,"
            + " ArtworkTitle, ListingName, ArtistName, Specifications, Quantity, CurrencyCode, UnitPrice,"
            + " ManufacturingCostPerUnit, MarkupPerUnit, FeeRate, FeeAmount, ArtistProceeds, TaxAmount)"
            + " OUTPUT INSERTED.OrderItemId VALUES (?, ?, 'PHYSICAL', ?, ?, N'Sunset', N'Sunset A4 print', N'Painter',"
            + " ?, 2, 'VND', 90000, 60000, 30000, 0, 0, 60000, 0)",
            Long.class, orderId, listingId, seller.artworkId(), seller.artistId(), a4Specifications);
    }

    private boolean flag(Long listingId) {
        return Boolean.TRUE.equals(listingRow(listingId).get("CostReviewRequired"));
    }

    private byte[] rowVersion(Long listingId) {
        return (byte[]) listingRow(listingId).get("RowVer");
    }

    private Map<String, Object> listingRow(Long listingId) {
        return jdbc.queryForMap("SELECT Price, ManufacturingCost, ArtistMarkup, CostReviewRequired, RowVer"
            + " FROM dbo.Listing WHERE ListingId = ?", listingId);
    }

    private Map<String, Object> orderItemRow(Long orderItemId) {
        return jdbc.queryForMap("SELECT UnitPrice, ManufacturingCostPerUnit, MarkupPerUnit, FeeRate, FeeAmount,"
            + " ArtistProceeds, TaxAmount, LineSubtotal, Quantity, Specifications FROM dbo.OrderItem"
            + " WHERE OrderItemId = ?", orderItemId);
    }

    private Long registerUser() {
        return customerService.register(new CustomerRegistration(
            "f07-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Painter")).getUserId();
    }

    private StaffAccount admin() {
        return staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive())
            .findFirst()
            .orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount("f07-test-" + UUID.randomUUID(),
                "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now())));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private record Seller(Long userId, Long artistId, Long artworkId) {
    }
}
