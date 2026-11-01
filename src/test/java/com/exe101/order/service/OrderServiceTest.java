package com.exe101.order.service;

import com.exe101.address.model.Address;
import com.exe101.address.model.ShippingAddress;
import com.exe101.address.service.AddressService;
import com.exe101.cart.config.CheckoutProperties;
import com.exe101.cart.dto.CheckoutPreviewDTO;
import com.exe101.cart.mapper.CartMapperImpl;
import com.exe101.cart.repository.CartItemRepository;
import com.exe101.cart.repository.CartRepository;
import com.exe101.cart.service.CartServiceImpl;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.Customer;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.Cart;
import com.exe101.entity.CartItem;
import com.exe101.entity.CartItemId;
import com.exe101.entity.MarketplaceOrder;
import com.exe101.entity.OrderItem;
import com.exe101.entity.OrderItemAsset;
import com.exe101.entity.OrderShippingAddress;
import com.exe101.entity.Payment;
import com.exe101.listing.model.ListingAsset;
import com.exe101.listing.model.ListingType;
import com.exe101.listing.model.PurchaseItem;
import com.exe101.listing.model.PurchaseOffer;
import com.exe101.listing.service.ListingService;
import com.exe101.order.config.OrderProperties;
import com.exe101.order.mapper.OrderEntityMapperImpl;
import com.exe101.order.model.CommitLine;
import com.exe101.order.model.OrderCommit;
import com.exe101.order.model.OrderStatus;
import com.exe101.order.repository.ItemFulfillmentRepository;
import com.exe101.order.repository.ItemRefundRepository;
import com.exe101.order.repository.OrderItemAssetRepository;
import com.exe101.order.repository.OrderItemRepository;
import com.exe101.order.repository.OrderRepository;
import com.exe101.order.repository.OrderShippingAddressRepository;
import com.exe101.order.repository.PaymentRepository;
import com.exe101.province.service.ProvinceService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Order creation runs against the real Cart service so the amounts are those a preview computes; only
// repositories and other features' services are mocked. Lenient: each test uses part of the shared persistence.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {
    private static long buyerId = 42L;
    private static long artistAUserId = 50L;
    private static long artistBUserId = 51L;
    private static long artistAId = 500L;
    private static long artistBId = 501L;
    private static long cartId = 7L;
    private static long addressId = 9L;
    private static long digitalId = 10L;
    private static long physicalId = 11L;
    private static long secondDigitalId = 12L;
    private static String provinceCode = "01";
    private static String a4Specifications = "Fine-art print, A4 (210 x 297 mm), 230 gsm matte paper";
    private static byte[] version = {0, 0, 0, 0, 0, 0, 0, 1};
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository itemRepository;
    @Mock
    private OrderItemAssetRepository assetRepository;
    @Mock
    private OrderShippingAddressRepository shippingRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private ItemFulfillmentRepository fulfillmentRepository;
    @Mock
    private ItemRefundRepository refundRepository;
    @Mock
    private CartRepository cartRepository;
    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private ListingService listingService;
    @Mock
    private AddressService addressService;
    @Mock
    private ProvinceService provinceService;
    @Mock
    private CustomerService customerService;
    private OrderServiceImpl service;
    private List<OrderItem> savedItems;
    private List<OrderItemAsset> savedAssets;

    @BeforeEach
    void setUp() {
        // 10% tax so per-line half-up rounding is exercised; 30.000 VND shipping; 30-minute payment window.
        service = service(new BigDecimal("30000"));
        savedItems = new ArrayList<>();
        savedAssets = new ArrayList<>();
        givenPersistence();
        when(customerService.getActive(buyerId)).thenReturn(Customer.builder()
            .withUserId(buyerId).withEmail("buyer@example.com").withDisplayName("Buyer").build());
    }

    @Test
    void create_order_mixed_artists_ok() {
        // given
        givenCart(cartItem(digitalId, 1), cartItem(physicalId, 2));
        givenListings(digital(digitalId, "50005", artistAUserId), physical(physicalId, artistBUserId));
        givenSupportedAddress();

        // when
        var result = service.create(buyerId, commit(addressId, "327006", line(digitalId, 1, "50005"),
            line(physicalId, 2, "110000")));

        // then
        verify(orderRepository).saveAndFlush(any());
        assertAll(
            () -> assertThat(savedItems).extracting(OrderItem::getListingId).containsExactly(digitalId, physicalId),
            () -> assertThat(savedItems).extracting(OrderItem::getArtistId).containsExactly(artistAId, artistBId),
            () -> assertThat(savedItems).extracting(OrderItem::getArtistName)
                .containsExactly("Artist " + artistAId, "Artist " + artistBId),
            () -> assertThat(savedItems).extracting(OrderItem::getOrderId).containsOnly(result.getOrderId())
        );
    }

    @Test
    void create_order_amounts_ok() {
        // given
        givenCart(cartItem(digitalId, 1), cartItem(physicalId, 2));
        givenListings(digital(digitalId, "50005", artistAUserId), physical(physicalId, artistBUserId));
        givenSupportedAddress();

        // when
        service.create(buyerId, commit(addressId, "327006", line(digitalId, 1, "50005"),
            line(physicalId, 2, "110000")));

        // then
        // Lines: 50.005 (tax 5.000,5 -> 5.001) and 2 x 110.000 = 220.000 (tax 22.000); shipping 30.000.
        var order = savedOrder();
        assertAll(
            () -> assertThat(savedItems).extracting(OrderItem::getUnitPrice)
                .containsExactly(new BigDecimal("50005.0000"), new BigDecimal("110000.0000")),
            () -> assertThat(savedItems).extracting(OrderItem::getTaxAmount)
                .containsExactly(new BigDecimal("5001.0000"), new BigDecimal("22000.0000")),
            () -> assertThat(order.getItemsSubtotal()).isEqualTo(new BigDecimal("270005.0000")),
            () -> assertThat(order.getShippingAmount()).isEqualTo(new BigDecimal("30000.0000")),
            () -> assertThat(order.getTaxAmount()).isEqualTo(new BigDecimal("27001.0000")),
            () -> assertThat(order.getCurrencyCode()).isEqualTo("VND"),
            () -> assertThat(savedPayment().getAmount()).isEqualTo(new BigDecimal("327006.0000"))
        );
    }

    @Test
    void create_order_tax_rounding_half_up_ok() {
        // given
        givenCart(cartItem(digitalId, 1), cartItem(secondDigitalId, 1));
        givenListings(digital(digitalId, "50005", artistAUserId), digital(secondDigitalId, "10005", artistAUserId));

        // when
        service.create(buyerId, commit(null, "66012", line(digitalId, 1, "50005"), line(secondDigitalId, 1, "10005")));

        // then
        // 5.000,5 -> 5.001 and 1.000,5 -> 1.001 per line; the Order tax is their sum, not 6.001 from the exact total.
        assertAll(
            () -> assertThat(savedItems).extracting(OrderItem::getTaxAmount)
                .containsExactly(new BigDecimal("5001.0000"), new BigDecimal("1001.0000")),
            () -> assertThat(savedOrder().getTaxAmount()).isEqualTo(new BigDecimal("6002.0000"))
        );
    }

    @Test
    void create_order_digital_only_no_shipping_ok() {
        // given
        givenCart(cartItem(digitalId, 1));
        givenListings(digital(digitalId, "50005", artistAUserId));

        // when
        var result = service.create(buyerId, commit(null, "55006", line(digitalId, 1, "50005")));

        // then
        assertAll(
            () -> assertThat(savedOrder().getShippingAmount()).isEqualTo(new BigDecimal("0.0000")),
            () -> assertThat(result.getShippingAddress()).isNull()
        );
        verify(shippingRepository, never()).saveAndFlush(any());
        verify(addressService, never()).selectForCheckout(any(), any());
    }

    @Test
    void create_order_item_snapshots_ok() {
        // given
        givenCart(cartItem(digitalId, 1), cartItem(physicalId, 2));
        givenListings(digital(digitalId, "50005", artistAUserId), physical(physicalId, artistBUserId));
        givenSupportedAddress();

        // when
        service.create(buyerId, commit(addressId, "327006", line(digitalId, 1, "50005"),
            line(physicalId, 2, "110000")));

        // then
        // No Platform Fee: rate and amount 0; digital proceeds = price, physical proceeds = quantity x markup.
        var digitalItem = savedItems.getFirst();
        var physicalItem = savedItems.getLast();
        assertAll(
            () -> assertThat(digitalItem.getListingType()).isEqualTo("DIGITAL"),
            () -> assertThat(digitalItem.getArtworkTitle()).isEqualTo("Artwork " + digitalId),
            () -> assertThat(digitalItem.getListingName()).isEqualTo("Listing " + digitalId),
            () -> assertThat(digitalItem.getQuantity()).isEqualTo(1),
            () -> assertThat(digitalItem.getFeeRate()).isEqualTo(new BigDecimal("0.000000")),
            () -> assertThat(digitalItem.getFeeAmount()).isEqualTo(new BigDecimal("0.0000")),
            () -> assertThat(digitalItem.getArtistProceeds()).isEqualTo(new BigDecimal("50005.0000")),
            () -> assertThat(digitalItem.getManufacturingCostPerUnit()).isNull(),
            () -> assertThat(digitalItem.getMarkupPerUnit()).isNull(),
            () -> assertThat(physicalItem.getListingType()).isEqualTo("PHYSICAL"),
            () -> assertThat(physicalItem.getQuantity()).isEqualTo(2),
            () -> assertThat(physicalItem.getSpecifications()).isEqualTo(a4Specifications),
            () -> assertThat(physicalItem.getManufacturingCostPerUnit()).isEqualTo(new BigDecimal("80000.0000")),
            () -> assertThat(physicalItem.getMarkupPerUnit()).isEqualTo(new BigDecimal("30000.0000")),
            () -> assertThat(physicalItem.getFeeAmount()).isEqualTo(new BigDecimal("0.0000")),
            () -> assertThat(physicalItem.getArtistProceeds()).isEqualTo(new BigDecimal("60000.0000"))
        );
    }

    @Test
    void create_order_asset_snapshot_ok() {
        // given
        givenCart(cartItem(digitalId, 1), cartItem(physicalId, 1));
        givenListings(digital(digitalId, "50005", artistAUserId), physical(physicalId, artistBUserId));
        givenSupportedAddress();

        // when
        var result = service.create(buyerId, commit(addressId, "206006", line(digitalId, 1, "50005"),
            line(physicalId, 1, "110000")));

        // then
        var digitalItemId = savedItems.getFirst().getOrderItemId();
        var physicalItemId = savedItems.getLast().getOrderItemId();
        assertAll(
            () -> assertThat(savedAssets).extracting(asset -> asset.getId().getOrderItemId(),
                    asset -> asset.getId().getListingAssetId(), OrderItemAsset::getListingId,
                    OrderItemAsset::getFileId)
                .containsExactly(
                    tuple(digitalItemId, 1000L + digitalId, digitalId, 2000L + digitalId),
                    tuple(digitalItemId, 1100L + digitalId, digitalId, 2100L + digitalId),
                    tuple(physicalItemId, 1000L + physicalId, physicalId, 2000L + physicalId),
                    tuple(physicalItemId, 1100L + physicalId, physicalId, 2100L + physicalId)),
            () -> assertThat(result.getItems().getFirst().getAssets()).extracting("listingAssetId")
                .containsExactly(1000L + digitalId, 1100L + digitalId)
        );
    }

    @Test
    void create_order_shipping_snapshot_ok() {
        // given
        givenCart(cartItem(physicalId, 1));
        givenListings(physical(physicalId, artistBUserId));
        givenSupportedAddress();

        // when
        service.create(buyerId, commit(addressId, "151000", line(physicalId, 1, "110000")));

        // then
        var captor = ArgumentCaptor.forClass(OrderShippingAddress.class);
        verify(shippingRepository).saveAndFlush(captor.capture());
        var snapshot = captor.getValue();
        assertAll(
            () -> assertThat(snapshot.getOrderId()).isEqualTo(savedOrder().getOrderId()),
            () -> assertThat(snapshot.getSourceAddressId()).isEqualTo(addressId),
            () -> assertThat(snapshot.getRecipientName()).isEqualTo("Nguyễn Văn A"),
            () -> assertThat(snapshot.getLine1()).isEqualTo("1 Tràng Tiền"),
            () -> assertThat(snapshot.getProvinceCode()).isEqualTo(provinceCode),
            () -> assertThat(snapshot.getProvinceName()).isEqualTo("Hà Nội"),
            () -> assertThat(snapshot.getCountryCode()).isEqualTo("VN")
        );
    }

    @Test
    void create_order_expiry_and_payment_target_ok() {
        // given
        givenCart(cartItem(digitalId, 1));
        givenListings(digital(digitalId, "50005", artistAUserId));

        // when
        var result = service.create(buyerId, commit(null, "55006", line(digitalId, 1, "50005")));

        // then
        var order = savedOrder();
        var payment = savedPayment();
        assertAll(
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT.name()),
            () -> assertThat(order.getCustomerId()).isEqualTo(buyerId),
            () -> assertThat(order.getExpiresAt()).isEqualTo(order.getCreatedAt().plusMinutes(30)),
            () -> assertThat(order.getPaidAt()).isNull(),
            () -> assertThat(order.getBillingName()).isEqualTo("Buyer"),
            () -> assertThat(order.getCustomerEmailSnapshot()).isEqualTo("buyer@example.com"),
            () -> assertThat(payment.getOrderId()).isEqualTo(order.getOrderId()),
            () -> assertThat(payment.getStatus()).isEqualTo("PENDING"),
            () -> assertThat(payment.getAmount()).isEqualTo(new BigDecimal("55006.0000")),
            () -> assertThat(payment.getCurrencyCode()).isEqualTo("VND"),
            () -> assertThat(result.getPayment().getAmount()).isEqualTo(new BigDecimal("55006.0000"))
        );
    }

    @Test
    void create_order_clears_cart_ok() {
        // given
        givenCart(cartItem(digitalId, 1));
        givenListings(digital(digitalId, "50005", artistAUserId));

        // when
        service.create(buyerId, commit(null, "55006", line(digitalId, 1, "50005")));

        // then
        verify(cartItemRepository).deleteByCartId(cartId);
    }

    @Test
    void create_order_price_changed_ko() {
        // given
        givenCart(cartItem(digitalId, 1));
        givenListings(digital(digitalId, "60000", artistAUserId));

        // when / then
        assertThatThrownBy(() -> service.create(buyerId, commit(null, "55006", line(digitalId, 1, "50005"))))
            .isInstanceOfSatisfying(AppException.class, exception -> assertAll(
                () -> assertThat(exception.getError()).isEqualTo(AppErrorMessage.CHECKOUT_CHANGED),
                () -> assertThat(((CheckoutPreviewDTO) exception.getDetails()).lines().getFirst().unitPrice())
                    .isEqualTo(new BigDecimal("60000.0000")),
                () -> assertThat(((CheckoutPreviewDTO) exception.getDetails()).totalAmount())
                    .isEqualTo(new BigDecimal("66000.0000"))
            ));
        verify(orderRepository, never()).saveAndFlush(any());
        verify(cartItemRepository, never()).deleteByCartId(cartId);
    }

    @Test
    void create_order_cart_changed_ko() {
        // given
        givenCart(cartItem(digitalId, 1), cartItem(physicalId, 2));
        givenListings(digital(digitalId, "50005", artistAUserId), physical(physicalId, artistBUserId));
        givenSupportedAddress();
        when(cartRepository.touch(eq(cartId), eq(new byte[] {9}), any())).thenReturn(0);
        var staleVersion = OrderCommit.builder().withCartVersion(new byte[] {9}).withAddressId(addressId)
            .withLines(List.of(line(digitalId, 1, "50005"), line(physicalId, 2, "110000")))
            .withTotalAmount(new BigDecimal("327006")).build();
        var otherQuantity = commit(addressId, "327006", line(digitalId, 1, "50005"), line(physicalId, 1, "110000"));
        var missingLine = commit(addressId, "327006", line(digitalId, 1, "50005"));

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> service.create(buyerId, staleVersion))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.CART_CONFLICT.getDetail()),
            () -> assertThatThrownBy(() -> service.create(buyerId, otherQuantity))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.CHECKOUT_CHANGED.getDetail()),
            () -> assertThatThrownBy(() -> service.create(buyerId, missingLine))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.CHECKOUT_CHANGED.getDetail())
        );
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_order_listing_unavailable_ko() {
        // given
        givenCart(cartItem(digitalId, 1));
        givenListings(unavailable(digital(digitalId, "50005", artistAUserId)));

        // when / then
        assertBlocked(commit(null, "55006", line(digitalId, 1, "50005")), "LISTING_UNAVAILABLE");
    }

    @Test
    void create_order_own_listing_ko() {
        // given
        givenCart(cartItem(digitalId, 1));
        givenListings(digital(digitalId, "50005", buyerId));

        // when / then
        assertBlocked(commit(null, "55006", line(digitalId, 1, "50005")), "OWN_LISTING");
    }

    @Test
    void create_order_empty_cart_ko() {
        // given
        givenCart();
        givenListings();

        // when / then
        assertBlocked(commit(null, "0", line(digitalId, 1, "50005")), "CART_EMPTY");
    }

    @Test
    void create_order_address_required_ko() {
        // given
        givenCart(cartItem(physicalId, 1));
        givenListings(physical(physicalId, artistBUserId));

        // when / then
        assertBlocked(commit(null, "151000", line(physicalId, 1, "110000")), "ADDRESS_REQUIRED");
    }

    @Test
    void create_order_address_not_found_ko() {
        // given
        givenCart(cartItem(physicalId, 1));
        givenListings(physical(physicalId, artistBUserId));
        when(addressService.get(buyerId, addressId)).thenThrow(new AppException(AppErrorMessage.ADDRESS_NOT_FOUND));

        // when / then
        assertThatThrownBy(() -> service.create(buyerId, commit(addressId, "151000", line(physicalId, 1, "110000"))))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ADDRESS_NOT_FOUND.getDetail());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_order_delivery_not_supported_ko() {
        // given
        givenCart(cartItem(physicalId, 1));
        givenListings(physical(physicalId, artistBUserId));
        when(addressService.get(buyerId, addressId)).thenReturn(Address.builder().withAddressId(addressId)
            .withUserId(buyerId).withProvinceCode(provinceCode).build());
        when(provinceService.isSupported(provinceCode)).thenReturn(false);

        // when / then
        assertBlocked(commit(addressId, "151000", line(physicalId, 1, "110000")), "DELIVERY_NOT_SUPPORTED");
        verify(addressService, never()).selectForCheckout(any(), any());
    }

    @Test
    void create_order_amount_too_large_ko() {
        // given
        // 99 x 20.000.000.000.000 reaches 10^15, beyond what an Order Item line can store.
        givenCart(cartItem(physicalId, 99));
        givenListings(physical(physicalId, "19999999920000", "80000", artistBUserId));
        givenSupportedAddress();

        // when / then
        assertBlocked(commit(addressId, "0", line(physicalId, 99, "20000000000000")), "AMOUNT_TOO_LARGE");
    }

    @Test
    void create_order_zero_total_ko() {
        // given
        // Q01: with no shipping fee, a 0 VND physical item (cost and markup 0) would make a 0 VND Order.
        service = service(BigDecimal.ZERO);
        givenCart(cartItem(physicalId, 1));
        givenListings(physical(physicalId, "0", "0", artistBUserId));
        givenSupportedAddress();

        // when / then
        assertThatThrownBy(() -> service.create(buyerId, commit(addressId, "0", line(physicalId, 1, "0"))))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ORDER_TOTAL_ZERO.getDetail());
        verify(orderRepository, never()).saveAndFlush(any());
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void get_order_ok() {
        // given
        var order = storedOrder(300L);
        when(orderRepository.findByOrderIdAndCustomerId(300L, buyerId)).thenReturn(Optional.of(order));

        // when
        var result = service.get(buyerId, 300L);

        // then
        assertAll(
            () -> assertThat(result.getOrderId()).isEqualTo(300L),
            () -> assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT),
            () -> assertThat(result.getTotalAmount()).isEqualTo(new BigDecimal("55006.0000"))
        );
    }

    @Test
    void get_order_not_found_ko() {
        // given
        // Another Customer's Order is looked up with this Customer's ID and is not found.
        when(orderRepository.findByOrderIdAndCustomerId(300L, buyerId)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.get(buyerId, 300L))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ORDER_NOT_FOUND.getDetail());
    }

    @Test
    void list_orders_ok() {
        // given
        when(orderRepository.findByCustomerIdOrderByCreatedAtDescOrderIdDesc(buyerId))
            .thenReturn(List.of(storedOrder(302L), storedOrder(301L)));

        // when
        var result = service.listOwn(buyerId);

        // then
        assertThat(result).extracting("orderId").containsExactly(302L, 301L);
    }

    @Test
    void expire_orders_ok() {
        // given
        when(orderRepository.expireDue(any(), eq("PENDING_PAYMENT"), eq("EXPIRED"))).thenReturn(2);

        // when
        var result = service.expireDue();

        // then
        assertThat(result).isEqualTo(2);
        verify(paymentRepository).cancelForExpiredOrders("PENDING", "CANCELLED", "EXPIRED");
    }

    private void assertBlocked(OrderCommit commit, String problem) {
        assertThatThrownBy(() -> service.create(buyerId, commit))
            .isInstanceOfSatisfying(AppException.class, exception -> assertAll(
                () -> assertThat(exception.getError()).isEqualTo(AppErrorMessage.CHECKOUT_BLOCKED),
                () -> assertThat(((CheckoutPreviewDTO) exception.getDetails()).problems()).contains(problem)
            ));
        verify(orderRepository, never()).saveAndFlush(any());
        verify(paymentRepository, never()).saveAndFlush(any());
        verify(cartItemRepository, never()).deleteByCartId(cartId);
    }

    private OrderServiceImpl service(BigDecimal shippingFee) {
        var checkout = new CheckoutProperties(new BigDecimal("0.10"), shippingFee, 99);
        var cartService = new CartServiceImpl(cartRepository, cartItemRepository, listingService, addressService,
            provinceService, checkout);

        return new OrderServiceImpl(orderRepository, itemRepository, assetRepository, shippingRepository,
            paymentRepository, fulfillmentRepository, refundRepository, new OrderEntityMapperImpl(), cartService,
            new CartMapperImpl(),
            listingService, addressService, customerService,
            new OrderProperties(Duration.ofMinutes(30), Duration.ofMinutes(1)));
    }

    // Saves get IDs, and the Order's TotalAmount is computed as SQL Server does; reads return what was saved.
    private void givenPersistence() {
        var ids = new AtomicLong(100);
        var order = new ArrayList<MarketplaceOrder>();
        var shipping = new ArrayList<OrderShippingAddress>();
        var payments = new ArrayList<Payment>();
        when(orderRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            MarketplaceOrder saved = invocation.getArgument(0);
            saved.setOrderId(ids.incrementAndGet());
            saved.setTotalAmount(saved.getItemsSubtotal().add(saved.getShippingAmount()).add(saved.getTaxAmount()));
            order.add(saved);
            return saved;
        });
        when(itemRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            OrderItem saved = invocation.getArgument(0);
            saved.setOrderItemId(ids.incrementAndGet());
            saved.setLineSubtotal(saved.getUnitPrice().multiply(BigDecimal.valueOf(saved.getQuantity())));
            savedItems.add(saved);
            return saved;
        });
        when(assetRepository.saveAllAndFlush(anyList())).thenAnswer(invocation -> {
            List<OrderItemAsset> saved = invocation.getArgument(0);
            savedAssets.addAll(saved);
            return saved;
        });
        when(shippingRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            shipping.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(paymentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Payment saved = invocation.getArgument(0);
            saved.setPaymentId(ids.incrementAndGet());
            payments.add(saved);
            return saved;
        });
        when(orderRepository.findById(any())).thenAnswer(invocation -> order.stream().findFirst());
        when(itemRepository.findByOrderIdInOrderByOrderItemIdAsc(anyCollection())).thenAnswer(invocation -> savedItems);
        when(assetRepository.findByIdOrderItemIdIn(anyCollection())).thenAnswer(invocation -> savedAssets);
        when(shippingRepository.findByOrderIdIn(anyCollection())).thenAnswer(invocation -> shipping);
        when(paymentRepository.findByOrderIdIn(anyCollection())).thenAnswer(invocation -> payments);
    }

    private void givenCart(CartItem... items) {
        when(cartRepository.findByUserId(buyerId)).thenReturn(Optional.of(cart()));
        when(cartRepository.touch(eq(cartId), eq(version), any())).thenReturn(1);
        when(cartItemRepository.findByIdCartIdOrderByIdListingIdAsc(cartId)).thenReturn(List.of(items));
    }

    // The same Listings are seen by the preview (offers) and by the locked read (purchase items).
    private void givenListings(PurchaseItem... items) {
        when(listingService.lockForPurchase(any())).thenAnswer(invocation -> matching(invocation.getArgument(0),
            items));
        when(listingService.findOffers(any())).thenAnswer(invocation -> matching(invocation.getArgument(0), items)
            .stream()
            .map(this::offer)
            .toList());
    }

    private List<PurchaseItem> matching(Collection<Long> listingIds, PurchaseItem... items) {
        return List.of(items).stream().filter(item -> listingIds.contains(item.getListingId())).toList();
    }

    private void givenSupportedAddress() {
        when(addressService.get(buyerId, addressId)).thenReturn(Address.builder().withAddressId(addressId)
            .withUserId(buyerId).withProvinceCode(provinceCode).build());
        when(provinceService.isSupported(provinceCode)).thenReturn(true);
        when(addressService.selectForCheckout(buyerId, addressId)).thenReturn(ShippingAddress.builder()
            .withSourceAddressId(addressId)
            .withRecipientName("Nguyễn Văn A")
            .withPhone("0912345678")
            .withLine1("1 Tràng Tiền")
            .withProvinceCode(provinceCode)
            .withProvinceName("Hà Nội")
            .withCountryCode("VN")
            .build());
    }

    private MarketplaceOrder savedOrder() {
        var captor = ArgumentCaptor.forClass(MarketplaceOrder.class);
        verify(orderRepository).saveAndFlush(captor.capture());

        return captor.getValue();
    }

    private Payment savedPayment() {
        var captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(captor.capture());

        return captor.getValue();
    }

    private MarketplaceOrder storedOrder(long orderId) {
        var created = OffsetDateTime.of(2026, 10, 31, 8, 0, 0, 0, ZoneOffset.UTC);
        var order = new MarketplaceOrder(buyerId, "PENDING_PAYMENT", created, created.plusMinutes(30), null, "VND",
            new BigDecimal("50005.0000"), new BigDecimal("0.0000"), new BigDecimal("5001.0000"),
            new BigDecimal("55006.0000"), "Buyer", null, "Buyer", "buyer@example.com", version);
        order.setOrderId(orderId);
        return order;
    }

    private OrderCommit commit(Long address, String totalAmount, CommitLine... lines) {
        return OrderCommit.builder()
            .withCartVersion(version)
            .withAddressId(address)
            .withLines(List.of(lines))
            .withTotalAmount(new BigDecimal(totalAmount))
            .build();
    }

    private CommitLine line(long listingId, int quantity, String unitPrice) {
        return CommitLine.builder()
            .withListingId(listingId)
            .withQuantity(quantity)
            .withUnitPrice(new BigDecimal(unitPrice))
            .build();
    }

    private Cart cart() {
        var cart = new Cart(buyerId, OffsetDateTime.of(2026, 10, 31, 0, 0, 0, 0, ZoneOffset.UTC), version);
        cart.setCartId(cartId);
        return cart;
    }

    private CartItem cartItem(long listingId, int quantity) {
        return new CartItem(new CartItemId(cartId, listingId), quantity);
    }

    private PurchaseItem digital(long listingId, String price, long ownerUserId) {
        return item(listingId, ListingType.DIGITAL, ownerUserId)
            .withPrice(new BigDecimal(price).setScale(4))
            .build();
    }

    private PurchaseItem physical(long listingId, long ownerUserId) {
        return physical(listingId, "80000", "30000", ownerUserId);
    }

    private PurchaseItem physical(long listingId, String cost, String markup, long ownerUserId) {
        var costAmount = new BigDecimal(cost).setScale(4);
        var markupAmount = new BigDecimal(markup).setScale(4);

        return item(listingId, ListingType.PHYSICAL, ownerUserId)
            .withPrice(costAmount.add(markupAmount))
            .withManufacturingCost(costAmount)
            .withArtistMarkup(markupAmount)
            .withSpecifications(a4Specifications)
            .build();
    }

    // Two files per Listing: asset IDs 1000+/1100+ the Listing ID, file IDs 2000+/2100+.
    private PurchaseItem.PurchaseItemBuilder item(long listingId, ListingType type, long ownerUserId) {
        var artistId = ownerUserId == artistBUserId ? artistBId : artistAId;

        return PurchaseItem.builder()
            .withListingId(listingId)
            .withListingType(type)
            .withName("Listing " + listingId)
            .withCurrencyCode("VND")
            .withPurchasable(true)
            .withArtworkId(listingId + 100)
            .withArtworkTitle("Artwork " + listingId)
            .withArtistId(artistId)
            .withArtistUserId(ownerUserId)
            .withArtistName("Artist " + artistId)
            .withAssets(List.of(
                ListingAsset.builder().withAssetId(1000L + listingId).withFileId(2000L + listingId).build(),
                ListingAsset.builder().withAssetId(1100L + listingId).withFileId(2100L + listingId).build()));
    }

    private PurchaseItem unavailable(PurchaseItem item) {
        item.setPurchasable(false);
        return item;
    }

    private PurchaseOffer offer(PurchaseItem item) {
        return PurchaseOffer.builder()
            .withListingId(item.getListingId())
            .withListingType(item.getListingType())
            .withName(item.getName())
            .withCurrencyCode(item.getCurrencyCode())
            .withPrice(item.getPrice())
            .withPurchasable(item.isPurchasable())
            .withArtistUserId(item.getArtistUserId())
            .build();
    }
}
