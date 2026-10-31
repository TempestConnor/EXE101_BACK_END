package com.exe101.order.service;

import com.exe101.address.model.ShippingAddress;
import com.exe101.address.service.AddressService;
import com.exe101.cart.mapper.CartMapper;
import com.exe101.cart.model.CheckoutLine;
import com.exe101.cart.model.CheckoutPreview;
import com.exe101.cart.service.CartService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.Customer;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.MarketplaceOrder;
import com.exe101.entity.OrderItem;
import com.exe101.entity.OrderItemAsset;
import com.exe101.entity.OrderShippingAddress;
import com.exe101.entity.Payment;
import com.exe101.listing.model.ListingType;
import com.exe101.listing.model.PurchaseItem;
import com.exe101.listing.service.ListingService;
import com.exe101.order.config.OrderProperties;
import com.exe101.order.mapper.OrderEntityMapper;
import com.exe101.order.model.CommitLine;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderAsset;
import com.exe101.order.model.OrderCommit;
import com.exe101.order.model.OrderLine;
import com.exe101.order.model.OrderStatus;
import com.exe101.order.model.PaymentStatus;
import com.exe101.order.model.PaymentTarget;
import com.exe101.order.repository.OrderItemAssetRepository;
import com.exe101.order.repository.OrderItemRepository;
import com.exe101.order.repository.OrderRepository;
import com.exe101.order.repository.OrderShippingAddressRepository;
import com.exe101.order.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private static String currencyCode = "VND";
    private static int moneyScale = 4;
    private static int rateScale = 6;
    private final OrderRepository orderRepository;
    private final OrderItemRepository itemRepository;
    private final OrderItemAssetRepository assetRepository;
    private final OrderShippingAddressRepository shippingRepository;
    private final PaymentRepository paymentRepository;
    private final OrderEntityMapper mapper;
    private final CartService cartService;
    private final CartMapper cartMapper;
    private final ListingService listingService;
    private final AddressService addressService;
    private final CustomerService customerService;
    private final OrderProperties properties;

    // One transaction: claim the reviewed Cart, lock its Listings, Artwork and Artists, recompute the preview under
    // those locks, compare it with what the Customer confirmed, then write every snapshot or nothing.
    @Override
    public Order create(Long userId, OrderCommit commit) {
        Customer customer = customerService.getActive(userId);
        List<Long> listingIds = cartService.claimForOrder(userId, commit.getCartVersion());
        Map<Long, PurchaseItem> items = listingService.lockForPurchase(listingIds).stream()
            .collect(Collectors.toMap(PurchaseItem::getListingId, Function.identity()));
        CheckoutPreview preview = cartService.preview(userId, commit.getAddressId());

        if (!preview.isCommittable()) {
            throw new AppException(AppErrorMessage.CHECKOUT_BLOCKED, cartMapper.toDto(preview));
        }

        if (!confirmed(commit, preview)) {
            throw new AppException(AppErrorMessage.CHECKOUT_CHANGED, cartMapper.toDto(preview));
        }

        // Q01 (approved 31 October 2026): a Payment must be above 0, so a 0 VND Order is not created.
        if (preview.getTotalAmount().signum() == 0) {
            throw new AppException(AppErrorMessage.ORDER_TOTAL_ZERO);
        }

        boolean physical = preview.getLines().stream()
            .anyMatch(line -> line.getListingType() == ListingType.PHYSICAL);
        ShippingAddress shipping = physical ? addressService.selectForCheckout(userId, commit.getAddressId()) : null;
        OffsetDateTime createdAt = now();
        Order order = Order.builder()
            .withCustomerId(userId)
            .withStatus(OrderStatus.PENDING_PAYMENT)
            .withCreatedAt(createdAt)
            .withExpiresAt(createdAt.plus(properties.paymentWindow()))
            .withCurrencyCode(currencyCode)
            .withItemsSubtotal(preview.getItemsSubtotal())
            .withShippingAmount(preview.getShippingAmount())
            .withTaxAmount(preview.getTaxAmount())
            .withBillingName(customer.getDisplayName())
            .withCustomerName(customer.getDisplayName())
            .withCustomerEmail(customer.getEmail())
            .build();
        MarketplaceOrder saved = orderRepository.saveAndFlush(mapper.toEntity(order));
        Long orderId = saved.getOrderId();
        preview.getLines().forEach(line -> saveItem(orderId, toOrderLine(items.get(line.getListingId()), line)));

        if (shipping != null) {
            shippingRepository.saveAndFlush(mapper.toEntity(shipping, orderId));
        }

        PaymentTarget payment = PaymentTarget.builder()
            .withOrderId(orderId)
            .withAmount(saved.getTotalAmount())
            .withCurrencyCode(currencyCode)
            .withStatus(PaymentStatus.PENDING)
            .withCreatedAt(createdAt)
            .build();
        paymentRepository.saveAndFlush(mapper.toEntity(payment));
        cartService.clear(userId);
        log.info("[ORDER] - ACTION: create: userId: {}: orderId: {}: totalAmount: {}", userId, orderId,
            saved.getTotalAmount());

        return assemble(List.of(orderRepository.findById(orderId).orElseThrow())).getFirst();
    }

    @Override
    public Order get(Long userId, Long orderId) {
        MarketplaceOrder entity = orderRepository.findByOrderIdAndCustomerId(orderId, userId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ORDER_NOT_FOUND));

        return assemble(List.of(entity)).getFirst();
    }

    @Override
    public List<Order> listOwn(Long userId) {
        return assemble(orderRepository.findByCustomerIdOrderByCreatedAtDescOrderIdDesc(userId));
    }

    // Expires every pending Order whose payment window has closed, and cancels its pending Payment.
    @Override
    public int expireDue() {
        int expired = orderRepository.expireDue(now(), OrderStatus.PENDING_PAYMENT.name(),
            OrderStatus.EXPIRED.name());

        if (expired == 0) {
            return 0;
        }

        paymentRepository.cancelForExpiredOrders(PaymentStatus.PENDING.name(), PaymentStatus.CANCELLED.name(),
            OrderStatus.EXPIRED.name());
        log.info("[ORDER] - ACTION: expireDue: expired: {}", expired);

        return expired;
    }

    // The Cart version was checked when the Cart was claimed. Lines, quantities and unit prices must equal the
    // recomputed preview, and so must the total, which also covers a changed tax rate or shipping fee.
    private boolean confirmed(OrderCommit commit, CheckoutPreview preview) {
        Map<Long, CommitLine> confirmedLines = commit.getLines().stream()
            .collect(Collectors.toMap(CommitLine::getListingId, Function.identity(), (first, second) -> first));
        boolean sameLines = commit.getLines().size() == preview.getLines().size()
            && preview.getLines().stream().allMatch(line -> sameLine(confirmedLines.get(line.getListingId()), line));
        boolean sameTotal = commit.getTotalAmount().compareTo(preview.getTotalAmount()) == 0;

        return sameLines && sameTotal;
    }

    private boolean sameLine(CommitLine confirmed, CheckoutLine current) {
        if (confirmed == null) {
            return false;
        }

        return Objects.equals(confirmed.getQuantity(), current.getQuantity())
            && confirmed.getUnitPrice().compareTo(current.getUnitPrice()) == 0;
    }

    // No Platform Fee (amendment B.5): fee rate and amount are 0 and the Artist receives the whole fee base,
    // the price for a digital item and the markup for a physical one.
    private OrderLine toOrderLine(PurchaseItem item, CheckoutLine line) {
        boolean physical = item.getListingType() == ListingType.PHYSICAL;
        BigDecimal quantity = BigDecimal.valueOf(line.getQuantity());
        BigDecimal feeAmount = money(BigDecimal.ZERO);
        BigDecimal feeBase = physical ? item.getArtistMarkup() : line.getUnitPrice();
        List<OrderAsset> assets = item.getAssets().stream()
            .map(asset -> OrderAsset.builder()
                .withListingAssetId(asset.getAssetId())
                .withFileId(asset.getFileId())
                .build())
            .toList();

        return OrderLine.builder()
            .withListingId(item.getListingId())
            .withListingType(item.getListingType())
            .withArtworkId(item.getArtworkId())
            .withArtistId(item.getArtistId())
            .withArtworkTitle(item.getArtworkTitle())
            .withListingName(item.getName())
            .withArtistName(item.getArtistName())
            .withSpecifications(item.getSpecifications())
            .withQuantity(line.getQuantity())
            .withCurrencyCode(currencyCode)
            .withUnitPrice(line.getUnitPrice())
            .withManufacturingCostPerUnit(physical ? money(item.getManufacturingCost()) : null)
            .withMarkupPerUnit(physical ? money(item.getArtistMarkup()) : null)
            .withFeeRate(BigDecimal.ZERO.setScale(rateScale))
            .withFeeAmount(feeAmount)
            .withArtistProceeds(money(feeBase.multiply(quantity)).subtract(feeAmount))
            .withTaxAmount(line.getTaxAmount())
            .withAssets(assets)
            .build();
    }

    private void saveItem(Long orderId, OrderLine line) {
        OrderItem saved = itemRepository.saveAndFlush(mapper.toEntity(line, orderId));
        List<OrderItemAsset> assets = line.getAssets().stream()
            .map(asset -> mapper.toEntity(asset, saved.getOrderItemId(), line.getListingId()))
            .toList();
        assetRepository.saveAllAndFlush(assets);
    }

    private List<Order> assemble(List<MarketplaceOrder> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }

        List<Long> orderIds = entities.stream().map(MarketplaceOrder::getOrderId).toList();
        List<OrderItem> items = itemRepository.findByOrderIdInOrderByOrderItemIdAsc(orderIds);
        List<Long> itemIds = items.stream().map(OrderItem::getOrderItemId).toList();
        Map<Long, List<OrderAsset>> assets = itemIds.isEmpty() ? Map.of() : assetRepository
            .findByIdOrderItemIdIn(itemIds).stream()
            .sorted(Comparator.comparingLong(asset -> asset.getId().getListingAssetId()))
            .collect(Collectors.groupingBy(asset -> asset.getId().getOrderItemId(),
                Collectors.mapping(mapper::toModel, Collectors.toList())));
        Map<Long, List<OrderLine>> lines = items.stream()
            .collect(Collectors.groupingBy(OrderItem::getOrderId, Collectors.mapping(item -> {
                OrderLine line = mapper.toModel(item);
                line.setAssets(assets.getOrDefault(item.getOrderItemId(), List.of()));

                return line;
            }, Collectors.toList())));
        Map<Long, ShippingAddress> shipping = shippingRepository.findByOrderIdIn(orderIds).stream()
            .collect(Collectors.toMap(OrderShippingAddress::getOrderId, mapper::toModel));
        Map<Long, PaymentTarget> payments = paymentRepository.findByOrderIdIn(orderIds).stream()
            .collect(Collectors.toMap(Payment::getOrderId, mapper::toModel));

        return entities.stream()
            .map(entity -> {
                Order order = mapper.toModel(entity);
                order.setItems(lines.getOrDefault(entity.getOrderId(), List.of()));
                order.setShippingAddress(shipping.get(entity.getOrderId()));
                order.setPayment(payments.get(entity.getOrderId()));

                return order;
            })
            .toList();
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(moneyScale);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
