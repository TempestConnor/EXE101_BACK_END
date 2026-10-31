package com.exe101.cart.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.artwork.repository.ArtworkRepository;
import com.exe101.cart.model.CartItemChange;
import com.exe101.cart.service.CartService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.repository.CustomerRepository;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.Artwork;
import com.exe101.entity.Listing;
import com.exe101.entity.StaffAccount;
import com.exe101.listing.repository.ListingRepository;
import com.exe101.staff.repository.StaffRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class CartPersistenceTest {
    private static String a4Specifications = "Fine-art print, A4 (210 x 297 mm), 230 gsm matte paper";
    private static int concurrentAdds = 4;
    @Autowired
    private CartService service;
    @Autowired
    private CartRepository cartRepository;
    @Autowired
    private ListingRepository listingRepository;
    @Autowired
    private ArtworkRepository artworkRepository;
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
    private Optional<StaffAccount> existingAdmin;
    private StaffAccount admin;
    private Long buyerId;
    private Long sellerId;
    private ArtistProfile artist;
    private Artwork artwork;
    private Listing digital;
    private Listing physical;

    // Committed fixtures: the concurrency tests need rows other transactions can see.
    @BeforeEach
    void setUp() {
        existingAdmin = existingAdmin();
        admin = existingAdmin.orElseGet(this::newAdmin);
        buyerId = registerUser();
        sellerId = registerUser();
        artist = artistRepository.saveAndFlush(new ArtistProfile(sellerId, "Painter", "ACTIVE", false,
            admin.getStaffId(), now(), null));
        artwork = artworkRepository.saveAndFlush(new Artwork(artist.getArtistId(), "Sunset", null, "2D",
            "PUBLISHED", now(), now(), null));
        digital = listingRepository.saveAndFlush(new Listing(artwork.getArtworkId(), "DIGITAL", "Sunset download",
            null, "PUBLISHED", "VND", new BigDecimal("50000"), null, null, null, false, now(), now(), null));
        physical = listingRepository.saveAndFlush(new Listing(artwork.getArtworkId(), "PHYSICAL", "Sunset A4 print",
            null, "PUBLISHED", "VND", new BigDecimal("110000"), new BigDecimal("80000"), new BigDecimal("30000"),
            a4Specifications, false, now(), now(), null));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE ci FROM dbo.CartItem ci JOIN dbo.Cart c ON c.CartId = ci.CartId WHERE c.UserId = ?",
            buyerId);
        jdbc.update("DELETE FROM dbo.Cart WHERE UserId = ?", buyerId);
        listingRepository.deleteById(digital.getListingId());
        listingRepository.deleteById(physical.getListingId());
        artworkRepository.deleteById(artwork.getArtworkId());
        artistRepository.deleteById(artist.getArtistId());
        customerRepository.deleteById(buyerId);
        customerRepository.deleteById(sellerId);

        if (existingAdmin.isEmpty()) {
            staffRepository.deleteById(admin.getStaffId());
        }
    }

    @Test
    void concurrent_first_add_creates_one_cart_ok() throws Exception {
        // given / when
        var outcomes = addPhysicalConcurrently();

        // then
        var successes = outcomes.stream().filter(outcome -> outcome == null).count();
        assertAll(
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.Cart WHERE UserId = ?", Integer.class,
                buyerId)).isEqualTo(1),
            () -> assertThat(successes).isPositive(),
            () -> assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome == null
                || AppErrorMessage.CART_CONFLICT.getDetail().equals(outcome.getMessage())).isTrue()),
            () -> assertThat(quantity(physical.getListingId())).isEqualTo((int) successes)
        );
    }

    @Test
    void concurrent_cart_edits_no_lost_update_ok() {
        // given
        service.addItem(buyerId, change(physical.getListingId(), 1));
        var stale = new TransactionTemplate(transactionManager);
        var other = new TransactionTemplate(transactionManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // when / then
        stale.executeWithoutResult(status -> {
            // This transaction holds the Cart at its current version while another request changes it.
            cartRepository.findByUserId(buyerId).orElseThrow();
            other.executeWithoutResult(inner -> service.updateItem(buyerId, physical.getListingId(), 2));
            assertThatThrownBy(() -> service.updateItem(buyerId, physical.getListingId(), 3))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.CART_CONFLICT.getDetail());
            status.setRollbackOnly();
        });
        assertThat(quantity(physical.getListingId())).isEqualTo(2);
    }

    @Test
    void cart_item_change_bumps_cart_version_ok() {
        // given
        var added = service.addItem(buyerId, change(physical.getListingId(), 1));

        // when
        var updated = service.updateItem(buyerId, physical.getListingId(), 2);

        // then
        var stored = jdbc.queryForObject("SELECT RowVer FROM dbo.Cart WHERE UserId = ?", byte[].class, buyerId);
        assertAll(
            () -> assertThat(updated.getVersion()).isNotEqualTo(added.getVersion()),
            () -> assertThat(updated.getVersion()).isEqualTo(stored)
        );
    }

    @Test
    void preview_uses_persisted_current_price_ok() {
        // given
        service.addItem(buyerId, change(digital.getListingId(), 1));
        jdbc.update("UPDATE dbo.Listing SET Price = 60000 WHERE ListingId = ?", digital.getListingId());

        // when
        var preview = service.preview(buyerId, null);

        // then
        // Configured demo values: 0% tax; no shipping for a digital-only Cart.
        assertAll(
            () -> assertThat(preview.getLines().getFirst().getUnitPrice()).isEqualTo(new BigDecimal("60000.0000")),
            () -> assertThat(preview.getItemsSubtotal()).isEqualTo(new BigDecimal("60000.0000")),
            () -> assertThat(preview.getTaxAmount()).isEqualTo(new BigDecimal("0.0000")),
            () -> assertThat(preview.getShippingAmount()).isEqualTo(new BigDecimal("0.0000")),
            () -> assertThat(preview.getTotalAmount()).isEqualTo(new BigDecimal("60000.0000")),
            () -> assertThat(preview.isCommittable()).isTrue()
        );
    }

    // Each outcome is null on success or the exception thrown.
    private List<Throwable> addPhysicalConcurrently() throws Exception {
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(concurrentAdds)) {
            List<Future<Throwable>> futures = IntStream.range(0, concurrentAdds)
                .mapToObj(index -> executor.submit(() -> {
                    start.await();

                    try {
                        service.addItem(buyerId, change(physical.getListingId(), 1));
                        return (Throwable) null;
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                }))
                .toList();
            start.countDown();

            return futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    return exception;
                }
            }).toList();
        }
    }

    private int quantity(Long listingId) {
        return jdbc.queryForObject("SELECT ci.Quantity FROM dbo.CartItem ci JOIN dbo.Cart c ON c.CartId = ci.CartId"
            + " WHERE c.UserId = ? AND ci.ListingId = ?", Integer.class, buyerId, listingId);
    }

    private CartItemChange change(Long listingId, int quantity) {
        return CartItemChange.builder().withListingId(listingId).withQuantity(quantity).build();
    }

    private Long registerUser() {
        return customerService.register(new CustomerRegistration(
            "f09-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Buyer")).getUserId();
    }

    private Optional<StaffAccount> existingAdmin() {
        return staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive())
            .findFirst();
    }

    private StaffAccount newAdmin() {
        return staffRepository.saveAndFlush(new StaffAccount("f09-test-" + UUID.randomUUID(),
            "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now()));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
