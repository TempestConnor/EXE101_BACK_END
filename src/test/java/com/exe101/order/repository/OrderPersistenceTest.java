package com.exe101.order.repository;

import com.exe101.address.model.AddressDetails;
import com.exe101.address.service.AddressService;
import com.exe101.artist.repository.ArtistRepository;
import com.exe101.artwork.repository.ArtworkRepository;
import com.exe101.cart.dto.CheckoutPreviewDTO;
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
import com.exe101.entity.SupportedProvince;
import com.exe101.listing.repository.ListingRepository;
import com.exe101.order.model.CommitLine;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderCommit;
import com.exe101.order.model.OrderStatus;
import com.exe101.order.service.OrderService;
import com.exe101.province.repository.ProvinceRepository;
import com.exe101.staff.repository.StaffRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

// Orders and their snapshots are immutable (database triggers), so no Order is ever committed here: each test
// rolls its transaction back, or checks that a failed creation left nothing behind. The Catalog, Customer, address
// and Cart fixtures are committed so concurrent transactions can see them, and removed afterwards.
// 10% tax exercises per-line half-up rounding; the expiry job is slowed so it does not run during a test.
@SpringBootTest(properties = {"app.checkout.tax-rate=0.10", "app.orders.expiry-interval=PT1H"})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class OrderPersistenceTest {
    private static String a4Specifications = "Fine-art print, A4 (210 x 297 mm), 230 gsm matte paper";
    private static long blockedMillis = 500;
    private static long waitSeconds = 30;
    @Autowired
    private OrderService service;
    @Autowired
    private CartService cartService;
    @Autowired
    private AddressService addressService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private ListingRepository listingRepository;
    @Autowired
    private ArtworkRepository artworkRepository;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private ProvinceRepository provinceRepository;
    @Autowired
    private StaffRepository staffRepository;
    @MockitoSpyBean
    private PaymentRepository paymentRepository;
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
    private SupportedProvince province;
    private Long addressId;

    @BeforeEach
    void setUp() {
        existingAdmin = existingAdmin();
        admin = existingAdmin.orElseGet(this::newAdmin);
        buyerId = registerUser("Buyer");
        sellerId = registerUser("Painter");
        artist = artistRepository.saveAndFlush(new ArtistProfile(sellerId, "Painter", "ACTIVE", false,
            admin.getStaffId(), now(), null));
        artwork = artworkRepository.saveAndFlush(new Artwork(artist.getArtistId(), "Sunset", null, "2D",
            "PUBLISHED", now(), now(), null));
        digital = listingRepository.saveAndFlush(new Listing(artwork.getArtworkId(), "DIGITAL", "Sunset download",
            null, "PUBLISHED", "VND", new BigDecimal("50005"), null, null, null, false, now(), now(), null));
        physical = listingRepository.saveAndFlush(new Listing(artwork.getArtworkId(), "PHYSICAL", "Sunset A4 print",
            null, "PUBLISHED", "VND", new BigDecimal("110000"), new BigDecimal("80000"), new BigDecimal("30000"),
            a4Specifications, false, now(), now(), null));
        // A test-only code outside the official two-digit range, so it never collides with real rows.
        province = provinceRepository.saveAndFlush(new SupportedProvince(
            "T" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000), "Test Province", true, now()));
        addressId = addressService.create(buyerId, AddressDetails.builder()
            .withRecipientName("Nguyễn Văn A")
            .withPhone("0912345678")
            .withLine1("1 Tràng Tiền")
            .withWard("Phường Hoàn Kiếm")
            .withProvinceCode(province.getProvinceCode())
            .build()).getAddressId();
        cartService.addItem(buyerId, CartItemChange.builder().withListingId(digital.getListingId())
            .withQuantity(1).build());
        cartService.addItem(buyerId, CartItemChange.builder().withListingId(physical.getListingId())
            .withQuantity(2).build());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE ci FROM dbo.CartItem ci JOIN dbo.Cart c ON c.CartId = ci.CartId WHERE c.UserId = ?",
            buyerId);
        jdbc.update("DELETE FROM dbo.Cart WHERE UserId = ?", buyerId);
        jdbc.update("DELETE FROM dbo.CustomerAddress WHERE UserId = ?", buyerId);
        provinceRepository.deleteById(province.getProvinceId());
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
    void order_persisted_totals_consistent_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var fileIds = addDigitalFiles();
            var order = service.create(buyerId, confirmedCommit());

            // Lines: 50.005 (tax 5.000,5 -> 5.001) and 2 x 110.000 (tax 22.000); shipping 30.000.
            var amounts = jdbc.queryForMap("SELECT ItemsSubtotal, ShippingAmount, TaxAmount, TotalAmount"
                + " FROM dbo.MarketplaceOrder WHERE OrderId = ?", order.getOrderId());
            var sums = jdbc.queryForMap("SELECT SUM(LineSubtotal) Lines, SUM(TaxAmount) Tax, SUM(FeeAmount) Fees"
                + " FROM dbo.OrderItem WHERE OrderId = ?", order.getOrderId());
            var proceeds = jdbc.queryForList("SELECT ArtistProceeds FROM dbo.OrderItem WHERE OrderId = ?"
                + " ORDER BY ListingId", BigDecimal.class, order.getOrderId());
            var payment = jdbc.queryForMap("SELECT Amount, Status FROM dbo.Payment WHERE OrderId = ?",
                order.getOrderId());
            var recordedFiles = jdbc.queryForList("SELECT a.FileId FROM dbo.OrderItemAsset a JOIN dbo.OrderItem i"
                + " ON i.OrderItemId = a.OrderItemId WHERE i.OrderId = ? ORDER BY a.FileId", Long.class,
                order.getOrderId());
            assertAll(
                () -> assertThat(amounts.get("ItemsSubtotal")).isEqualTo(new BigDecimal("270005.0000")),
                () -> assertThat(amounts.get("ShippingAmount")).isEqualTo(new BigDecimal("30000.0000")),
                () -> assertThat(amounts.get("TaxAmount")).isEqualTo(new BigDecimal("27001.0000")),
                () -> assertThat(amounts.get("TotalAmount")).isEqualTo(new BigDecimal("327006.0000")),
                () -> assertThat(sums.get("Lines")).isEqualTo(amounts.get("ItemsSubtotal")),
                () -> assertThat(sums.get("Tax")).isEqualTo(amounts.get("TaxAmount")),
                () -> assertThat(sums.get("Fees")).isEqualTo(new BigDecimal("0.0000")),
                () -> assertThat(proceeds).containsExactly(new BigDecimal("50005.0000"),
                    new BigDecimal("60000.0000")),
                () -> assertThat(payment.get("Amount")).isEqualTo(new BigDecimal("327006.0000")),
                () -> assertThat(payment.get("Status")).isEqualTo("PENDING"),
                () -> assertThat(recordedFiles).isEqualTo(fileIds),
                () -> assertThat(order.getTotalAmount()).isEqualTo(new BigDecimal("327006.0000")),
                () -> assertThat(cartItemCount()).isZero()
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void order_survives_catalog_changes_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var created = service.create(buyerId, confirmedCommit());
            jdbc.update("UPDATE dbo.Listing SET Status = 'UNLISTED', Price = 99999 WHERE ListingId = ?",
                digital.getListingId());
            jdbc.update("UPDATE dbo.Listing SET Status = 'UNLISTED' WHERE ListingId = ?", physical.getListingId());
            jdbc.update("UPDATE dbo.CustomerAddress SET Line1 = N'99 Changed Street' WHERE AddressId = ?",
                addressId);

            var stored = service.get(buyerId, created.getOrderId());
            assertAll(
                () -> assertThat(stored.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT),
                () -> assertThat(stored.getExpiresAt()).isAfter(now()),
                () -> assertThat(stored.getTotalAmount()).isEqualTo(created.getTotalAmount()),
                () -> assertThat(stored.getItems().getFirst().getUnitPrice())
                    .isEqualTo(new BigDecimal("50005.0000")),
                () -> assertThat(stored.getItems().getFirst().getListingName()).isEqualTo("Sunset download"),
                () -> assertThat(stored.getShippingAddress().getLine1()).isEqualTo("1 Tràng Tiền"),
                () -> assertThat(stored.getPayment().getAmount()).isEqualTo(created.getTotalAmount())
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void order_creation_rollback_ko() {
        // given
        // The Payment insert comes last: by then the Order, its items, files and shipping snapshot are written.
        var commit = confirmedCommit();
        var version = cartVersion();
        doThrow(new IllegalStateException("payment insert failed")).when(paymentRepository).saveAndFlush(any());

        // when
        assertThatThrownBy(() -> service.create(buyerId, commit)).isInstanceOf(IllegalStateException.class);

        // then
        assertAll(
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.MarketplaceOrder WHERE CustomerId = ?",
                Integer.class, buyerId)).isZero(),
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.OrderItem WHERE ListingId IN (?, ?)",
                Integer.class, digital.getListingId(), physical.getListingId())).isZero(),
            () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.OrderShippingAddress"
                + " WHERE SourceAddressId = ?", Integer.class, addressId)).isZero(),
            () -> assertThat(cartItemCount()).isEqualTo(2),
            () -> assertThat(cartVersion()).isEqualTo(version)
        );
    }

    @Test
    void order_concurrent_price_change_ok() throws Exception {
        // given
        var confirmed = confirmedCommit();
        var created = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var updated = new CountDownLatch(1);
        var commitUpdate = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // 1. An Order holds its Listings: a price change waits until the Order transaction ends.
            Future<Order> creator = executor.submit(() -> inRolledBackTransaction(() -> {
                var order = service.create(buyerId, confirmed);
                created.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return order;
            }));
            created.await(waitSeconds, TimeUnit.SECONDS);
            Future<Integer> priceChange = executor.submit(() -> jdbc.update(
                "UPDATE dbo.Listing SET Price = 70000 WHERE ListingId = ?", digital.getListingId()));
            var updateWaited = isBlocked(priceChange);
            release.countDown();
            var order = creator.get(waitSeconds, TimeUnit.SECONDS);
            priceChange.get(waitSeconds, TimeUnit.SECONDS);

            // 2. A price change in progress: the Order waits for it, then sees the new price and is rejected.
            var reconfirmed = confirmedCommit();
            Future<Integer> pendingChange = executor.submit(() -> new TransactionTemplate(transactionManager)
                .execute(status -> {
                    var rows = jdbc.update("UPDATE dbo.Listing SET Price = 80000 WHERE ListingId = ?",
                        digital.getListingId());
                    updated.countDown();
                    await(commitUpdate);
                    return rows;
                }));
            updated.await(waitSeconds, TimeUnit.SECONDS);
            Future<Order> lateCreator = executor.submit(() -> inRolledBackTransaction(() ->
                service.create(buyerId, reconfirmed)));
            var orderWaited = isBlocked(lateCreator);
            commitUpdate.countDown();
            pendingChange.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(updateWaited).isTrue(),
                () -> assertThat(order.getItems().getFirst().getUnitPrice()).isEqualTo(new BigDecimal("50005.0000")),
                () -> assertThat(orderWaited).isTrue(),
                () -> assertThatThrownBy(() -> lateCreator.get(waitSeconds, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(AppException.class, exception -> assertAll(
                        () -> assertThat(exception.getError()).isEqualTo(AppErrorMessage.CHECKOUT_CHANGED),
                        () -> assertThat(((CheckoutPreviewDTO) exception.getDetails()).lines().getFirst()
                            .unitPrice()).isEqualTo(new BigDecimal("80000.0000"))
                    )),
                () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.MarketplaceOrder"
                    + " WHERE CustomerId = ?", Integer.class, buyerId)).isZero()
            );
        }
    }

    @Test
    void order_duplicate_submit_ko() throws Exception {
        // given
        var commit = confirmedCommit();
        var created = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The first submission resubmits the same Cart in its own transaction, then holds the Cart while a
            // concurrent submission of the same Cart arrives. Both roll back, since Orders cannot be removed.
            Future<FirstSubmission> first = executor.submit(() -> inRolledBackTransaction(() -> {
                service.create(buyerId, commit);
                var resubmitted = catchFailure(() -> service.create(buyerId, commit));
                var orders = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.MarketplaceOrder WHERE CustomerId = ?",
                    Integer.class, buyerId);
                created.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return new FirstSubmission(resubmitted, orders);
            }));
            created.await(waitSeconds, TimeUnit.SECONDS);
            Future<Order> concurrent = executor.submit(() -> inRolledBackTransaction(() ->
                service.create(buyerId, commit)));
            var concurrentWaited = isBlocked(concurrent);
            release.countDown();
            var outcome = first.get(waitSeconds, TimeUnit.SECONDS);
            concurrent.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(outcome.orders()).isEqualTo(1),
                () -> assertThat(outcome.resubmitted()).isInstanceOf(AppException.class)
                    .hasMessage(AppErrorMessage.CART_CONFLICT.getDetail()),
                () -> assertThat(concurrentWaited).isTrue()
            );
        }
    }

    @Test
    void expire_orders_conditional_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var overdue = pendingOrder(-120, -60);
            var open = pendingOrder(0, 60);
            var paid = paidOrder();

            service.expireDue();

            assertAll(
                () -> assertThat(orderStatus(overdue)).isEqualTo("EXPIRED"),
                () -> assertThat(paymentStatus(overdue)).isEqualTo("CANCELLED"),
                () -> assertThat(orderStatus(open)).isEqualTo("PENDING_PAYMENT"),
                () -> assertThat(paymentStatus(open)).isEqualTo("PENDING"),
                () -> assertThat(orderStatus(paid)).isEqualTo("PAID"),
                () -> assertThat(paymentStatus(paid)).isEqualTo("SUCCEEDED")
            );
            status.setRollbackOnly();
        });
    }

    // Built from a fresh preview, as a client sends back what the Customer confirmed.
    private OrderCommit confirmedCommit() {
        var preview = cartService.preview(buyerId, addressId);
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

    // Files can be added only while a Listing has never been published; the caller's transaction rolls back.
    private List<Long> addDigitalFiles() {
        jdbc.update("UPDATE dbo.Listing SET PublishedAt = NULL WHERE ListingId = ?", digital.getListingId());
        var first = storedFile();
        var second = storedFile();
        jdbc.update("INSERT INTO dbo.ListingAsset (ListingId, FileId, SortOrder, CreatedAt)"
            + " VALUES (?, ?, 0, ?), (?, ?, 1, ?)", digital.getListingId(), first, now(), digital.getListingId(),
            second, now());
        jdbc.update("UPDATE dbo.Listing SET PublishedAt = ? WHERE ListingId = ?", now(), digital.getListingId());

        return List.of(first, second);
    }

    private long storedFile() {
        return jdbc.queryForObject("INSERT INTO dbo.StoredFile (StorageKey, OriginalName, ContentType, ByteLength,"
                + " AccessClass, CreatedAt) OUTPUT INSERTED.FileId VALUES (?, N'sunset.png', 'image/png', 12,"
                + " 'PURCHASED', ?)",
            Long.class, "protected/f10-test-" + UUID.randomUUID(), now());
    }

    private long pendingOrder(int createdMinutes, int expiresMinutes) {
        var orderId = jdbc.queryForObject("INSERT INTO dbo.MarketplaceOrder (CustomerId, Status, CreatedAt,"
                + " ExpiresAt, ItemsSubtotal) OUTPUT INSERTED.OrderId VALUES (?, 'PENDING_PAYMENT', ?, ?, 50000)",
            Long.class, buyerId, now().plusMinutes(createdMinutes), now().plusMinutes(expiresMinutes));
        jdbc.update("INSERT INTO dbo.Payment (OrderId, Amount) VALUES (?, 50000)", orderId);

        return orderId;
    }

    private long paidOrder() {
        var orderId = jdbc.queryForObject("INSERT INTO dbo.MarketplaceOrder (CustomerId, Status, CreatedAt,"
                + " ExpiresAt, PaidAt, ItemsSubtotal) OUTPUT INSERTED.OrderId VALUES (?, 'PAID', ?, ?, ?, 50000)",
            Long.class, buyerId, now().minusMinutes(120), now().minusMinutes(60), now().minusMinutes(90));
        jdbc.update("INSERT INTO dbo.Payment (OrderId, Amount, Status, SucceededAt) VALUES (?, 50000,"
            + " 'SUCCEEDED', ?)", orderId, now().minusMinutes(90));

        return orderId;
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.MarketplaceOrder WHERE OrderId = ?", String.class,
            orderId);
    }

    private String paymentStatus(long orderId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.Payment WHERE OrderId = ?", String.class, orderId);
    }

    private int cartItemCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.CartItem ci JOIN dbo.Cart c ON c.CartId = ci.CartId"
            + " WHERE c.UserId = ?", Integer.class, buyerId);
    }

    private byte[] cartVersion() {
        return jdbc.queryForObject("SELECT RowVer FROM dbo.Cart WHERE UserId = ?", byte[].class, buyerId);
    }

    private <T> T inRolledBackTransaction(Callable<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            status.setRollbackOnly();

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

    private Throwable catchFailure(Runnable work) {
        try {
            work.run();
            return null;
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(waitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private Long registerUser(String displayName) {
        return customerService.register(new CustomerRegistration(
            "f10-test-" + UUID.randomUUID() + "@example.invalid", "long-password", displayName)).getUserId();
    }

    private Optional<StaffAccount> existingAdmin() {
        return staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive())
            .findFirst();
    }

    private StaffAccount newAdmin() {
        return staffRepository.saveAndFlush(new StaffAccount("f10-test-" + UUID.randomUUID(),
            "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now()));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private record FirstSubmission(Throwable resubmitted, int orders) {
    }
}
