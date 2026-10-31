package com.exe101.cart.service;

import com.exe101.address.model.Address;
import com.exe101.address.service.AddressService;
import com.exe101.cart.config.CheckoutProperties;
import com.exe101.cart.model.CartItemChange;
import com.exe101.cart.model.CheckoutProblem;
import com.exe101.cart.repository.CartItemRepository;
import com.exe101.cart.repository.CartRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.Cart;
import com.exe101.entity.CartItem;
import com.exe101.entity.CartItemId;
import com.exe101.listing.model.ListingType;
import com.exe101.listing.model.PurchaseOffer;
import com.exe101.listing.service.ListingService;
import com.exe101.province.service.ProvinceService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {
    private static long buyerId = 42L;
    private static long artistAId = 50L;
    private static long artistBId = 51L;
    private static long cartId = 7L;
    private static byte[] version = {0, 0, 0, 0, 0, 0, 0, 1};
    private static byte[] nextVersion = {0, 0, 0, 0, 0, 0, 0, 2};
    @Mock
    private CartRepository cartRepository;
    @Mock
    private CartItemRepository itemRepository;
    @Mock
    private ListingService listingService;
    @Mock
    private AddressService addressService;
    @Mock
    private ProvinceService provinceService;
    private CartServiceImpl service;

    @BeforeEach
    void setUp() {
        // 10% tax so rounding is exercised; 30.000 VND shipping; at most 99 per physical line.
        var checkout = new CheckoutProperties(new BigDecimal("0.10"), new BigDecimal("30000"), 99);
        service = new CartServiceImpl(cartRepository, itemRepository, listingService, addressService,
            provinceService, checkout);
    }

    @Test
    void get_cart_without_cart_ok() {
        // given
        when(cartRepository.findByUserId(buyerId)).thenReturn(Optional.empty());

        // when
        var result = service.get(buyerId);

        // then
        assertAll(
            () -> assertThat(result.getItems()).isEmpty(),
            () -> assertThat(result.getVersion()).isNull()
        );
        verify(cartRepository, never()).saveAndFlush(any());
    }

    @Test
    void get_cart_shows_current_price_ok() {
        // given
        givenCart(item(10L, 1));
        givenOffers(offer(10L, ListingType.DIGITAL, "120000", true, artistAId));

        // when
        var result = service.get(buyerId);

        // then
        var line = result.getItems().getFirst();
        assertAll(
            () -> assertThat(line.getUnitPrice()).isEqualByComparingTo("120000"),
            () -> assertThat(line.getLineTotal()).isEqualByComparingTo("120000"),
            () -> assertThat(line.getProblem()).isNull(),
            () -> assertThat(result.getVersion()).isEqualTo(version)
        );
    }

    @Test
    void add_item_creates_cart_ok() {
        // given
        var created = cart();
        when(cartRepository.findByUserId(buyerId)).thenReturn(Optional.empty());
        when(cartRepository.saveAndFlush(any())).thenReturn(created);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(created));
        when(itemRepository.findById(new CartItemId(cartId, 10L))).thenReturn(Optional.empty());
        when(itemRepository.findByIdCartIdOrderByIdListingIdAsc(cartId)).thenReturn(List.of(item(10L, 2)));
        givenOffers(offer(10L, ListingType.PHYSICAL, "110000", true, artistAId));

        // when
        var result = service.addItem(buyerId, change(10L, 2));

        // then
        var cartCaptor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).saveAndFlush(cartCaptor.capture());
        var itemCaptor = ArgumentCaptor.forClass(CartItem.class);
        verify(itemRepository).saveAndFlush(itemCaptor.capture());
        assertAll(
            () -> assertThat(cartCaptor.getValue().getUserId()).isEqualTo(buyerId),
            () -> assertThat(itemCaptor.getValue().getId()).isEqualTo(new CartItemId(cartId, 10L)),
            () -> assertThat(itemCaptor.getValue().getQuantity()).isEqualTo(2),
            () -> assertThat(result.getItems()).hasSize(1)
        );
    }

    @Test
    void add_items_multiple_artists_both_types_ok() {
        // given
        givenExistingCartForChange();
        when(itemRepository.findById(any())).thenReturn(Optional.empty());
        when(itemRepository.findByIdCartIdOrderByIdListingIdAsc(cartId))
            .thenReturn(List.of(item(10L, 1), item(20L, 1)));
        givenOffers(offer(10L, ListingType.DIGITAL, "50000", true, artistAId),
            offer(20L, ListingType.PHYSICAL, "110000", true, artistBId));

        // when
        service.addItem(buyerId, change(10L, 1));
        var result = service.addItem(buyerId, change(20L, 1));

        // then
        assertAll(
            () -> assertThat(result.getItems()).extracting("listingId").containsExactly(10L, 20L),
            () -> assertThat(result.getItems()).extracting("listingType")
                .containsExactly(ListingType.DIGITAL, ListingType.PHYSICAL),
            () -> assertThat(result.getItems()).allSatisfy(line -> assertThat(line.getProblem()).isNull())
        );
    }

    @Test
    void add_digital_quantity_not_one_ko() {
        // given
        givenOffers(offer(10L, ListingType.DIGITAL, "50000", true, artistAId));

        // when / then
        assertThatThrownBy(() -> service.addItem(buyerId, change(10L, 2)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.CART_QUANTITY_INVALID.getDetail());
        verify(itemRepository, never()).saveAndFlush(any());
        verify(cartRepository, never()).saveAndFlush(any());
    }

    @Test
    void add_physical_quantity_above_limit_ko() {
        // given
        givenOffers(offer(20L, ListingType.PHYSICAL, "110000", true, artistAId));

        // when / then
        assertThatThrownBy(() -> service.addItem(buyerId, change(20L, 100)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.CART_QUANTITY_INVALID.getDetail());
        verify(itemRepository, never()).saveAndFlush(any());
    }

    @Test
    void add_duplicate_physical_increments_ok() {
        // given
        givenExistingCartForChange();
        when(itemRepository.findById(new CartItemId(cartId, 20L))).thenReturn(Optional.of(item(20L, 2)));
        when(itemRepository.findByIdCartIdOrderByIdListingIdAsc(cartId)).thenReturn(List.of(item(20L, 5)));
        givenOffers(offer(20L, ListingType.PHYSICAL, "110000", true, artistAId));

        // when
        service.addItem(buyerId, change(20L, 3));

        // then
        var captor = ArgumentCaptor.forClass(CartItem.class);
        verify(itemRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualTo(5);
    }

    @Test
    void add_duplicate_digital_keeps_one_ok() {
        // given
        givenExistingCartForChange();
        when(itemRepository.findById(new CartItemId(cartId, 10L))).thenReturn(Optional.of(item(10L, 1)));
        when(itemRepository.findByIdCartIdOrderByIdListingIdAsc(cartId)).thenReturn(List.of(item(10L, 1)));
        givenOffers(offer(10L, ListingType.DIGITAL, "50000", true, artistAId));

        // when
        var result = service.addItem(buyerId, change(10L, 1));

        // then
        var captor = ArgumentCaptor.forClass(CartItem.class);
        verify(itemRepository).saveAndFlush(captor.capture());
        assertAll(
            () -> assertThat(captor.getValue().getQuantity()).isEqualTo(1),
            () -> assertThat(result.getItems().getFirst().getQuantity()).isEqualTo(1)
        );
    }

    @Test
    void add_listing_not_found_ko() {
        // given
        when(listingService.findOffers(List.of(99L))).thenReturn(List.of());

        // when / then
        assertThatThrownBy(() -> service.addItem(buyerId, change(99L, 1)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_NOT_FOUND.getDetail());
        verify(itemRepository, never()).saveAndFlush(any());
        verify(cartRepository, never()).saveAndFlush(any());
    }

    @Test
    void add_unpurchasable_listing_ko() {
        // given
        // Unpurchasable covers an unlisted Listing, unpublished Artwork, an inactive Artist or a pending cost review.
        givenOffers(offer(10L, ListingType.DIGITAL, "50000", false, artistAId));

        // when / then
        assertThatThrownBy(() -> service.addItem(buyerId, change(10L, 1)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_NOT_PURCHASABLE.getDetail());
        verify(itemRepository, never()).saveAndFlush(any());
        verify(cartRepository, never()).saveAndFlush(any());
    }

    @Test
    void add_own_listing_ko() {
        // given
        givenOffers(offer(10L, ListingType.DIGITAL, "50000", true, buyerId));

        // when / then
        assertThatThrownBy(() -> service.addItem(buyerId, change(10L, 1)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.SELF_PURCHASE_NOT_ALLOWED.getDetail());
        verify(itemRepository, never()).saveAndFlush(any());
        verify(cartRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_quantity_ok() {
        // given
        givenExistingCartForChange();
        when(itemRepository.findById(new CartItemId(cartId, 20L))).thenReturn(Optional.of(item(20L, 2)));
        when(itemRepository.findByIdCartIdOrderByIdListingIdAsc(cartId)).thenReturn(List.of(item(20L, 4)));
        givenOffers(offer(20L, ListingType.PHYSICAL, "110000", true, artistAId));

        // when
        var result = service.updateItem(buyerId, 20L, 4);

        // then
        var captor = ArgumentCaptor.forClass(CartItem.class);
        verify(itemRepository).saveAndFlush(captor.capture());
        assertAll(
            () -> assertThat(captor.getValue().getQuantity()).isEqualTo(4),
            () -> assertThat(result.getItems().getFirst().getLineTotal()).isEqualByComparingTo("440000")
        );
    }

    @Test
    void update_digital_quantity_ko() {
        // given
        when(cartRepository.findByUserId(buyerId)).thenReturn(Optional.of(cart()));
        when(itemRepository.findById(new CartItemId(cartId, 10L))).thenReturn(Optional.of(item(10L, 1)));
        givenOffers(offer(10L, ListingType.DIGITAL, "50000", true, artistAId));

        // when / then
        assertThatThrownBy(() -> service.updateItem(buyerId, 10L, 2))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.CART_QUANTITY_INVALID.getDetail());
        verify(itemRepository, never()).saveAndFlush(any());
        verify(cartRepository, never()).touch(anyLong(), any(), any());
    }

    @Test
    void update_item_not_in_cart_ko() {
        // given
        when(cartRepository.findByUserId(buyerId)).thenReturn(Optional.of(cart()));
        when(itemRepository.findById(new CartItemId(cartId, 20L))).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.updateItem(buyerId, 20L, 3))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.CART_ITEM_NOT_FOUND.getDetail());
        verify(itemRepository, never()).saveAndFlush(any());
    }

    @Test
    void remove_item_ok() {
        // given
        var existing = item(20L, 2);
        when(cartRepository.findByUserId(buyerId)).thenReturn(Optional.of(cart()));
        when(cartRepository.touch(eq(cartId), eq(version), any())).thenReturn(1);
        when(itemRepository.findById(new CartItemId(cartId, 20L))).thenReturn(Optional.of(existing));

        // when
        service.removeItem(buyerId, 20L);

        // then
        verify(itemRepository).delete(existing);
    }

    @Test
    void remove_item_not_in_cart_ko() {
        // given
        when(cartRepository.findByUserId(buyerId)).thenReturn(Optional.of(cart()));
        when(itemRepository.findById(new CartItemId(cartId, 20L))).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.removeItem(buyerId, 20L))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.CART_ITEM_NOT_FOUND.getDetail());
        verify(itemRepository, never()).delete(any());
    }

    @Test
    void preview_empty_cart_ko() {
        // given
        when(cartRepository.findByUserId(buyerId)).thenReturn(Optional.empty());

        // when
        var result = service.preview(buyerId, null);

        // then
        assertAll(
            () -> assertThat(result.getProblems()).containsExactly(CheckoutProblem.CART_EMPTY),
            () -> assertThat(result.isCommittable()).isFalse(),
            () -> assertThat(result.getTotalAmount()).isEqualByComparingTo("0")
        );
    }

    @Test
    void preview_price_changed_shows_current_price_ok() {
        // given
        // Added when the Listing cost 100; it now costs 120.
        givenCart(item(10L, 1));
        givenOffers(offer(10L, ListingType.DIGITAL, "120", true, artistAId));

        // when
        var result = service.preview(buyerId, null);

        // then
        assertAll(
            () -> assertThat(result.getLines().getFirst().getUnitPrice()).isEqualByComparingTo("120"),
            () -> assertThat(result.getItemsSubtotal()).isEqualByComparingTo("120"),
            () -> assertThat(result.getTotalAmount()).isEqualByComparingTo("132"),
            () -> assertThat(result.isCommittable()).isTrue()
        );
    }

    @Test
    void preview_mixed_cart_totals_ok() {
        // given
        givenCart(item(10L, 1), item(20L, 2));
        givenOffers(offer(10L, ListingType.DIGITAL, "50005", true, artistAId),
            offer(20L, ListingType.PHYSICAL, "110000", true, artistBId));
        givenAddress("01", true);

        // when
        var result = service.preview(buyerId, 3L);

        // then
        // Tax: 5.000,5 rounds half-up to 5.001; 220.000 x 10% = 22.000.
        assertAll(
            () -> assertThat(result.getLines()).extracting("taxAmount")
                .containsExactly(new BigDecimal("5001.0000"), new BigDecimal("22000.0000")),
            () -> assertThat(result.getItemsSubtotal()).isEqualTo(new BigDecimal("270005.0000")),
            () -> assertThat(result.getShippingAmount()).isEqualTo(new BigDecimal("30000.0000")),
            () -> assertThat(result.getTaxAmount()).isEqualTo(new BigDecimal("27001.0000")),
            () -> assertThat(result.getTotalAmount()).isEqualTo(new BigDecimal("327006.0000")),
            () -> assertThat(result.getCurrencyCode()).isEqualTo("VND"),
            () -> assertThat(result.isCommittable()).isTrue()
        );
    }

    @Test
    void preview_digital_only_needs_no_address_ok() {
        // given
        givenCart(item(10L, 1));
        givenOffers(offer(10L, ListingType.DIGITAL, "50000", true, artistAId));

        // when
        var result = service.preview(buyerId, null);

        // then
        assertAll(
            () -> assertThat(result.getShippingAmount()).isEqualByComparingTo("0"),
            () -> assertThat(result.getProblems()).isEmpty(),
            () -> assertThat(result.isCommittable()).isTrue()
        );
    }

    @Test
    void preview_physical_without_address_ko() {
        // given
        givenCart(item(20L, 1));
        givenOffers(offer(20L, ListingType.PHYSICAL, "110000", true, artistAId));

        // when
        var result = service.preview(buyerId, null);

        // then
        assertAll(
            () -> assertThat(result.getProblems()).containsExactly(CheckoutProblem.ADDRESS_REQUIRED),
            () -> assertThat(result.isCommittable()).isFalse()
        );
    }

    @Test
    void preview_unsupported_province_ko() {
        // given
        givenCart(item(20L, 1));
        givenOffers(offer(20L, ListingType.PHYSICAL, "110000", true, artistAId));
        givenAddress("79", false);

        // when
        var result = service.preview(buyerId, 3L);

        // then
        assertAll(
            () -> assertThat(result.getProblems()).containsExactly(CheckoutProblem.DELIVERY_NOT_SUPPORTED),
            () -> assertThat(result.isCommittable()).isFalse()
        );
    }

    @Test
    void preview_address_not_found_ko() {
        // given
        givenCart(item(20L, 1));
        givenOffers(offer(20L, ListingType.PHYSICAL, "110000", true, artistAId));
        when(addressService.get(buyerId, 3L)).thenThrow(new AppException(AppErrorMessage.ADDRESS_NOT_FOUND));

        // when / then
        assertThatThrownBy(() -> service.preview(buyerId, 3L))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ADDRESS_NOT_FOUND.getDetail());
    }

    @Test
    void preview_one_invalid_item_blocks_ok() {
        // given
        // Listing 11 was unlisted after it was added.
        givenCart(item(10L, 1), item(11L, 1));
        givenOffers(offer(10L, ListingType.DIGITAL, "50000", true, artistAId),
            offer(11L, ListingType.DIGITAL, "70000", false, artistBId));

        // when
        var result = service.preview(buyerId, null);

        // then
        assertAll(
            () -> assertThat(result.getLines()).extracting("problem")
                .containsExactly(null, CheckoutProblem.LISTING_UNAVAILABLE),
            () -> assertThat(result.getItemsSubtotal()).isEqualByComparingTo("50000"),
            () -> assertThat(result.getProblems()).containsExactly(CheckoutProblem.LISTING_UNAVAILABLE),
            () -> assertThat(result.isCommittable()).isFalse()
        );
        verify(itemRepository, never()).delete(any());
        verify(itemRepository, never()).saveAndFlush(any());
    }

    @Test
    void preview_own_listing_blocks_ko() {
        // given
        givenCart(item(10L, 1));
        givenOffers(offer(10L, ListingType.DIGITAL, "50000", true, buyerId));

        // when
        var result = service.preview(buyerId, null);

        // then
        assertAll(
            () -> assertThat(result.getLines().getFirst().getProblem()).isEqualTo(CheckoutProblem.OWN_LISTING),
            () -> assertThat(result.getProblems()).containsExactly(CheckoutProblem.OWN_LISTING),
            () -> assertThat(result.isCommittable()).isFalse()
        );
    }

    @Test
    void preview_returns_commit_reference_ok() {
        // given
        givenCart(item(10L, 1));
        givenOffers(offer(10L, ListingType.DIGITAL, "50000", true, artistAId));

        // when
        var result = service.preview(buyerId, null);

        // then
        // F10 receives the Cart version and these amounts back and rejects any difference.
        assertAll(
            () -> assertThat(result.getCartVersion()).isEqualTo(version),
            () -> assertThat(result.getLines().getFirst().getListingId()).isEqualTo(10L),
            () -> assertThat(result.getLines().getFirst().getUnitPrice()).isEqualTo(new BigDecimal("50000.0000")),
            () -> assertThat(result.getTotalAmount()).isEqualTo(new BigDecimal("55000.0000"))
        );
    }

    @Test
    void preview_amount_too_large_blocks_ko() {
        // given
        // The largest storable price (15 integer digits) twice is a line that decimal(19,4) cannot hold.
        givenCart(item(20L, 2));
        givenOffers(offer(20L, ListingType.PHYSICAL, "999999999999999", true, artistAId));
        givenAddress("01", true);

        // when
        var result = service.preview(buyerId, 3L);

        // then
        assertAll(
            () -> assertThat(result.getLines().getFirst().getLineTotal())
                .isEqualTo(new BigDecimal("1999999999999998.0000")),
            () -> assertThat(result.getProblems()).containsExactly(CheckoutProblem.AMOUNT_TOO_LARGE),
            () -> assertThat(result.isCommittable()).isFalse()
        );
    }

    private void givenCart(CartItem... items) {
        when(cartRepository.findByUserId(buyerId)).thenReturn(Optional.of(cart()));
        when(itemRepository.findByIdCartIdOrderByIdListingIdAsc(cartId)).thenReturn(List.of(items));
    }

    private void givenExistingCartForChange() {
        var touched = cart();
        touched.setRowVer(nextVersion);
        when(cartRepository.findByUserId(buyerId)).thenReturn(Optional.of(cart()));
        when(cartRepository.touch(eq(cartId), eq(version), any())).thenReturn(1);
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(touched));
    }

    private void givenOffers(PurchaseOffer... offers) {
        when(listingService.findOffers(any())).thenAnswer(invocation -> {
            List<Long> ids = List.copyOf(invocation.getArgument(0));
            return List.of(offers).stream().filter(offer -> ids.contains(offer.getListingId())).toList();
        });
    }

    private void givenAddress(String provinceCode, boolean supported) {
        when(addressService.get(buyerId, 3L))
            .thenReturn(Address.builder().withAddressId(3L).withUserId(buyerId).withProvinceCode(provinceCode).build());
        when(provinceService.isSupported(provinceCode)).thenReturn(supported);
    }

    private Cart cart() {
        var cart = new Cart(buyerId, OffsetDateTime.of(2026, 10, 31, 0, 0, 0, 0, ZoneOffset.UTC), version);
        cart.setCartId(cartId);
        return cart;
    }

    private CartItem item(long listingId, int quantity) {
        return new CartItem(new CartItemId(cartId, listingId), quantity);
    }

    private CartItemChange change(long listingId, int quantity) {
        return CartItemChange.builder().withListingId(listingId).withQuantity(quantity).build();
    }

    private PurchaseOffer offer(long listingId, ListingType type, String price, boolean purchasable, long ownerId) {
        return PurchaseOffer.builder()
            .withListingId(listingId)
            .withListingType(type)
            .withName("Listing " + listingId)
            .withCurrencyCode("VND")
            .withPrice(new BigDecimal(price).setScale(4))
            .withPurchasable(purchasable)
            .withArtistUserId(ownerId)
            .build();
    }
}
