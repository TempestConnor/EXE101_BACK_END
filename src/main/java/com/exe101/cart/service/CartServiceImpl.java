package com.exe101.cart.service;

import com.exe101.address.model.Address;
import com.exe101.address.service.AddressService;
import com.exe101.cart.config.CheckoutProperties;
import com.exe101.cart.model.Cart;
import com.exe101.cart.model.CartItemChange;
import com.exe101.cart.model.CartLine;
import com.exe101.cart.model.CheckoutLine;
import com.exe101.cart.model.CheckoutPreview;
import com.exe101.cart.model.CheckoutProblem;
import com.exe101.cart.repository.CartItemRepository;
import com.exe101.cart.repository.CartRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.persistence.DatabaseErrors;
import com.exe101.entity.CartItem;
import com.exe101.entity.CartItemId;
import com.exe101.listing.model.ListingType;
import com.exe101.listing.model.PurchaseOffer;
import com.exe101.listing.service.ListingService;
import com.exe101.province.service.ProvinceService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {
    private static String currencyCode = "VND";
    // Money is whole VND at scale 4. decimal(19,4) holds at most 15 integer digits; Order TotalAmount is (21,4).
    private static int moneyScale = 4;
    private static BigDecimal amountLimit = BigDecimal.TEN.pow(15);
    private static BigDecimal totalLimit = BigDecimal.TEN.pow(17);
    private final CartRepository cartRepository;
    private final CartItemRepository itemRepository;
    private final ListingService listingService;
    private final AddressService addressService;
    private final ProvinceService provinceService;
    private final CheckoutProperties checkout;

    @Override
    public Cart get(Long userId) {
        Optional<com.exe101.entity.Cart> cart = cartRepository.findByUserId(userId);

        if (cart.isEmpty()) {
            return Cart.builder().withItems(List.of()).build();
        }

        return assemble(userId, cart.get());
    }

    @Override
    public Cart addItem(Long userId, CartItemChange change) {
        if (change.getListingId() == null) {
            throw new AppException(AppErrorMessage.LISTING_NOT_FOUND);
        }

        PurchaseOffer offer = requireOffer(change.getListingId());
        requireQuantity(offer, change.getQuantity());

        if (offer.getArtistUserId().equals(userId)) {
            throw new AppException(AppErrorMessage.SELF_PURCHASE_NOT_ALLOWED);
        }

        if (!offer.isPurchasable()) {
            throw new AppException(AppErrorMessage.LISTING_NOT_PURCHASABLE);
        }

        Optional<com.exe101.entity.Cart> found = cartRepository.findByUserId(userId);
        long cartId = found.map(this::touch).orElseGet(() -> createCart(userId));
        CartItemId itemId = new CartItemId(cartId, offer.getListingId());
        Optional<CartItem> existing = itemRepository.findById(itemId);
        // A digital Listing stays at 1 when added again; a physical one adds to the quantity already there.
        int quantity = change.getQuantity();

        if (existing.isPresent() && offer.getListingType() == ListingType.PHYSICAL) {
            quantity += existing.get().getQuantity();
            requireQuantity(offer, quantity);
        }

        CartItem item = existing.orElseGet(() -> new CartItem(itemId, 0));
        item.setQuantity(quantity);
        itemRepository.saveAndFlush(item);
        log.info("[CART] - ACTION: addItem: userId: {}: listingId: {}: quantity: {}", userId,
            offer.getListingId(), quantity);

        return assemble(userId, cartRepository.findById(cartId).orElseThrow());
    }

    @Override
    public Cart updateItem(Long userId, Long listingId, Integer quantity) {
        com.exe101.entity.Cart cart = requireCart(userId);
        CartItem item = requireItem(cart, listingId);
        requireQuantity(requireOffer(listingId), quantity);
        touch(cart);
        item.setQuantity(quantity);
        itemRepository.saveAndFlush(item);
        log.info("[CART] - ACTION: updateItem: userId: {}: listingId: {}: quantity: {}", userId, listingId,
            quantity);

        return assemble(userId, cartRepository.findById(cart.getCartId()).orElseThrow());
    }

    @Override
    public void removeItem(Long userId, Long listingId) {
        com.exe101.entity.Cart cart = requireCart(userId);
        CartItem item = requireItem(cart, listingId);
        touch(cart);
        itemRepository.delete(item);
        itemRepository.flush();
        log.info("[CART] - ACTION: removeItem: userId: {}: listingId: {}", userId, listingId);
    }

    @Override
    public CheckoutPreview preview(Long userId, Long addressId) {
        Optional<com.exe101.entity.Cart> cart = cartRepository.findByUserId(userId);
        List<CartLine> cartLines = cart.map(found -> assemble(userId, found).getItems()).orElse(List.of());
        // Lines with a problem are shown but not counted: the amounts are what the Customer could commit to.
        List<CheckoutLine> lines = cartLines.stream().map(this::toCheckoutLine).toList();
        List<CheckoutLine> counted = lines.stream().filter(line -> line.getProblem() == null).toList();
        boolean physical = counted.stream().anyMatch(line -> line.getListingType() == ListingType.PHYSICAL);
        Set<CheckoutProblem> problems = new LinkedHashSet<>();

        if (lines.isEmpty()) {
            problems.add(CheckoutProblem.CART_EMPTY);
        }

        lines.stream()
            .map(CheckoutLine::getProblem)
            .filter(problem -> problem != null)
            .forEach(problems::add);

        if (addressId != null) {
            // Another Customer's or a deleted address is not found; withdrawn support only blocks the checkout.
            Address address = addressService.get(userId, addressId);

            if (physical && !provinceService.isSupported(address.getProvinceCode())) {
                problems.add(CheckoutProblem.DELIVERY_NOT_SUPPORTED);
            }
        }

        if (physical && addressId == null) {
            problems.add(CheckoutProblem.ADDRESS_REQUIRED);
        }

        BigDecimal itemsSubtotal = sum(counted.stream().map(CheckoutLine::getLineTotal).toList());
        BigDecimal taxAmount = sum(counted.stream().map(CheckoutLine::getTaxAmount).toList());
        BigDecimal shippingAmount = physical ? money(checkout.shippingFee()) : money(BigDecimal.ZERO);
        BigDecimal totalAmount = itemsSubtotal.add(shippingAmount).add(taxAmount);
        boolean lineTooLarge = counted.stream().anyMatch(line -> line.getLineTotal().compareTo(amountLimit) >= 0
            || line.getTaxAmount().compareTo(amountLimit) >= 0);
        boolean tooLarge = lineTooLarge || itemsSubtotal.compareTo(amountLimit) >= 0
            || taxAmount.compareTo(amountLimit) >= 0 || totalAmount.compareTo(totalLimit) >= 0;

        if (tooLarge) {
            problems.add(CheckoutProblem.AMOUNT_TOO_LARGE);
        }

        return CheckoutPreview.builder()
            .withCartVersion(cart.map(com.exe101.entity.Cart::getRowVer).orElse(null))
            .withCurrencyCode(currencyCode)
            .withLines(lines)
            .withItemsSubtotal(itemsSubtotal)
            .withShippingAmount(shippingAmount)
            .withTaxAmount(taxAmount)
            .withTotalAmount(totalAmount)
            .withProblems(new ArrayList<>(problems))
            .withCommittable(problems.isEmpty())
            .build();
    }

    private PurchaseOffer requireOffer(Long listingId) {
        return listingService.findOffers(List.of(listingId)).stream()
            .findFirst()
            .orElseThrow(() -> new AppException(AppErrorMessage.LISTING_NOT_FOUND));
    }

    private void requireQuantity(PurchaseOffer offer, Integer quantity) {
        if (!quantityAllowed(offer.getListingType(), quantity)) {
            throw new AppException(AppErrorMessage.CART_QUANTITY_INVALID);
        }
    }

    // Digital: exactly one. Physical: 1 up to the configured limit.
    private boolean quantityAllowed(ListingType listingType, Integer quantity) {
        if (quantity == null || quantity < 1) {
            return false;
        }

        if (listingType == ListingType.DIGITAL) {
            return quantity == 1;
        }

        return quantity <= checkout.maxPhysicalQuantity();
    }

    private com.exe101.entity.Cart requireCart(Long userId) {
        return cartRepository.findByUserId(userId)
            .orElseThrow(() -> new AppException(AppErrorMessage.CART_ITEM_NOT_FOUND));
    }

    private CartItem requireItem(com.exe101.entity.Cart cart, Long listingId) {
        return itemRepository.findById(new CartItemId(cart.getCartId(), listingId))
            .orElseThrow(() -> new AppException(AppErrorMessage.CART_ITEM_NOT_FOUND));
    }

    private long createCart(Long userId) {
        com.exe101.entity.Cart entity = new com.exe101.entity.Cart();
        entity.setUserId(userId);
        entity.setUpdatedAt(now());

        try {
            return cartRepository.saveAndFlush(entity).getCartId();
        } catch (DataIntegrityViolationException exception) {
            // UQ on Cart.UserId: a concurrent first add created the Cart first.
            if (DatabaseErrors.isDuplicateKey(exception)) {
                throw new AppException(AppErrorMessage.CART_CONFLICT);
            }

            throw exception;
        }
    }

    private long touch(com.exe101.entity.Cart cart) {
        int updated = cartRepository.touch(cart.getCartId(), cart.getRowVer(), now());

        if (updated == 0) {
            throw new AppException(AppErrorMessage.CART_CONFLICT);
        }

        return cart.getCartId();
    }

    private Cart assemble(Long userId, com.exe101.entity.Cart cart) {
        List<CartItem> items = itemRepository.findByIdCartIdOrderByIdListingIdAsc(cart.getCartId());
        List<Long> listingIds = items.stream().map(item -> item.getId().getListingId()).toList();
        Map<Long, PurchaseOffer> offers = listingService.findOffers(listingIds).stream()
            .collect(Collectors.toMap(PurchaseOffer::getListingId, Function.identity()));
        List<CartLine> lines = items.stream()
            .map(item -> toLine(userId, offers.get(item.getId().getListingId()), item.getQuantity()))
            .toList();

        return Cart.builder()
            .withVersion(cart.getRowVer())
            .withItems(lines)
            .build();
    }

    private CartLine toLine(Long userId, PurchaseOffer offer, int quantity) {
        BigDecimal unitPrice = money(offer.getPrice());

        return CartLine.builder()
            .withListingId(offer.getListingId())
            .withListingType(offer.getListingType())
            .withName(offer.getName())
            .withCurrencyCode(offer.getCurrencyCode())
            .withUnitPrice(unitPrice)
            .withQuantity(quantity)
            .withLineTotal(money(unitPrice.multiply(BigDecimal.valueOf(quantity))))
            .withProblem(problemFor(userId, offer, quantity))
            .build();
    }

    private CheckoutProblem problemFor(Long userId, PurchaseOffer offer, int quantity) {
        if (offer.getArtistUserId().equals(userId)) {
            return CheckoutProblem.OWN_LISTING;
        }

        if (!offer.isPurchasable()) {
            return CheckoutProblem.LISTING_UNAVAILABLE;
        }

        if (!quantityAllowed(offer.getListingType(), quantity)) {
            return CheckoutProblem.QUANTITY_NOT_ALLOWED;
        }

        return null;
    }

    // Tax per line: line total x configured rate, rounded half-up to whole VND (approved 31 October 2026).
    private CheckoutLine toCheckoutLine(CartLine line) {
        BigDecimal tax = line.getLineTotal().multiply(checkout.taxRate()).setScale(0, RoundingMode.HALF_UP);

        return CheckoutLine.builder()
            .withListingId(line.getListingId())
            .withListingType(line.getListingType())
            .withName(line.getName())
            .withUnitPrice(line.getUnitPrice())
            .withQuantity(line.getQuantity())
            .withLineTotal(line.getLineTotal())
            .withTaxAmount(money(tax))
            .withProblem(line.getProblem())
            .build();
    }

    private BigDecimal sum(List<BigDecimal> amounts) {
        return money(amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(moneyScale);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
