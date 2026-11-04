package com.exe101.fulfillment.service;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entitlement.service.EntitlementService;
import com.exe101.entity.PhysicalFulfillment;
import com.exe101.fulfillment.config.FulfillmentProperties;
import com.exe101.fulfillment.mapper.FulfillmentEntityMapper;
import com.exe101.fulfillment.model.Fulfillment;
import com.exe101.fulfillment.model.FulfillmentChange;
import com.exe101.fulfillment.model.FulfillmentStatus;
import com.exe101.fulfillment.model.FulfillmentStep;
import com.exe101.fulfillment.repository.FulfillmentEventRepository;
import com.exe101.fulfillment.repository.FulfillmentRepository;
import com.exe101.fulfillment.repository.ReturnRequestRepository;
import com.exe101.listing.model.ListingType;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderStatus;
import com.exe101.order.model.ShippableItem;
import com.exe101.order.service.OrderService;
import com.exe101.proceeds.service.ProceedsService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
public class FulfillmentServiceImpl implements FulfillmentService {
    // Forward only, one step at a time; DELIVERED, FAILED and CANCELLED are final (approved 1 November 2026).
    private static Map<FulfillmentStatus, FulfillmentStatus> nextStep = Map.of(
        FulfillmentStatus.PENDING, FulfillmentStatus.IN_PRODUCTION,
        FulfillmentStatus.IN_PRODUCTION, FulfillmentStatus.READY_TO_SHIP,
        FulfillmentStatus.READY_TO_SHIP, FulfillmentStatus.SHIPPED,
        FulfillmentStatus.SHIPPED, FulfillmentStatus.DELIVERED);
    private static String rejectedRequest = "REJECTED";
    private final FulfillmentRepository repository;
    private final FulfillmentEventRepository eventRepository;
    private final ReturnRequestRepository returnRequestRepository;
    private final FulfillmentEntityMapper mapper;
    private final EntitlementService entitlementService;
    private final OrderService orderService;
    private final ProceedsService proceedsService;
    private final FulfillmentProperties properties;

    // §9.10: a paid Order starts every physical item at PENDING. Runs in the payment-success transaction, which
    // holds the Order lock; an item already started is skipped.
    @Override
    public List<Fulfillment> start(Order order) {
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("Order is not paid: " + order.getOrderId());
        }

        OffsetDateTime startedAt = now();
        List<Fulfillment> started = order.getItems().stream()
            .filter(line -> line.getListingType() == ListingType.PHYSICAL)
            .filter(line -> !repository.existsById(line.getOrderItemId()))
            .map(line -> begin(line.getOrderItemId(), startedAt))
            .toList();
        log.info("[FULFILLMENT] - ACTION: start: orderId: {}: started: {}", order.getOrderId(), started.size());

        return started;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Fulfillment> list(FulfillmentStatus status) {
        List<PhysicalFulfillment> entities = status == null
            ? repository.findAllByOrderByUpdatedAtDescOrderItemIdDesc()
            : repository.findByStatusOrderByUpdatedAtDescOrderItemIdDesc(status.name());

        if (entities.isEmpty()) {
            return List.of();
        }

        List<Long> itemIds = entities.stream().map(PhysicalFulfillment::getOrderItemId).toList();
        Map<Long, ShippableItem> shippable = orderService.listShippable(itemIds).stream()
            .collect(Collectors.toMap(ShippableItem::getOrderItemId, Function.identity()));

        return entities.stream()
            .map(entity -> describe(mapper.toModel(entity), shippable.get(entity.getOrderItemId())))
            .toList();
    }

    // Request checks come first, so a bad request locks nothing. Then the Order is locked before its item, the
    // order refunds will use, so a delivery and a refund decision for one item wait for each other.
    @Override
    public Fulfillment advance(Long staffId, Long orderItemId, FulfillmentChange change) {
        FulfillmentStatus target = change.getStatus();
        String carrier = blankToNull(change.getCarrier());
        String trackingReference = blankToNull(change.getTrackingReference());

        if (!nextStep.containsValue(target)) {
            throw new AppException(AppErrorMessage.FULFILLMENT_STATUS_CHANGE_NOT_ALLOWED);
        }

        boolean shipping = target == FulfillmentStatus.SHIPPED;
        boolean shipmentOutsideShipping = !shipping && (carrier != null || trackingReference != null);

        if (shipmentOutsideShipping || (trackingReference != null && carrier == null)) {
            throw new AppException(AppErrorMessage.FULFILLMENT_SHIPMENT_INVALID);
        }

        Order order = orderService.findLockedByItem(orderItemId)
            .orElseThrow(() -> new AppException(AppErrorMessage.FULFILLMENT_NOT_FOUND));
        PhysicalFulfillment entity = repository.findWithLockByOrderItemId(orderItemId)
            .orElseThrow(() -> notStarted(order, orderItemId));

        if (order.getStatus() != OrderStatus.PAID) {
            throw new AppException(AppErrorMessage.FULFILLMENT_NOT_STARTED);
        }

        FulfillmentStatus current = FulfillmentStatus.valueOf(entity.getStatus());

        if (nextStep.get(current) != target) {
            throw new AppException(AppErrorMessage.FULFILLMENT_STATUS_CHANGE_NOT_ALLOWED);
        }

        // The server's clock decides delivery, and with it when the return window closes.
        OffsetDateTime changedAt = now();
        entity.setStatus(target.name());
        entity.setUpdatedAt(changedAt);

        if (shipping) {
            entity.setCarrier(carrier);
            entity.setTrackingReference(trackingReference);
        }

        if (target == FulfillmentStatus.DELIVERED) {
            entity.setDeliveredAt(changedAt);
        }

        PhysicalFulfillment saved = repository.saveAndFlush(entity);
        record(orderItemId, current, target, blankToNull(change.getReason()), changedAt, staffId);
        log.info("[FULFILLMENT] - ACTION: advance: staffId: {}: orderItemId: {}: status: {}", staffId, orderItemId,
            target);

        // Read the same way as the list; the snapshots are immutable, so the Order lock is not needed for them.
        ShippableItem shippable = orderService.listShippable(List.of(orderItemId)).stream().findFirst().orElse(null);

        return describe(mapper.toModel(saved), shippable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> listWindowClosed() {
        return repository.findWindowClosed(FulfillmentStatus.DELIVERED.name(), windowClosedBefore());
    }

    // Amendment §C.9–10: the included files are granted once the 48-hour window after delivery has closed with no
    // open or approved return. Everything is checked again under the Order and item locks.
    @Override
    public boolean grantIfWindowClosed(Long orderItemId) {
        Optional<Order> found = orderService.findLockedByItem(orderItemId);
        Optional<PhysicalFulfillment> locked = found.flatMap(order -> repository.findWithLockByOrderItemId(
            orderItemId));

        if (locked.isEmpty()) {
            return false;
        }

        Order order = found.get();
        PhysicalFulfillment entity = locked.get();
        boolean delivered = FulfillmentStatus.DELIVERED.name().equals(entity.getStatus());
        boolean windowClosed = delivered && !entity.getDeliveredAt().isAfter(windowClosedBefore());
        boolean paid = order.getStatus() == OrderStatus.PAID;

        if (!windowClosed || !paid
            || returnRequestRepository.existsByOrderItemIdAndStatusNot(orderItemId, rejectedRequest)) {
            return false;
        }

        boolean granted = entitlementService.grantIncluded(order, orderItemId).isPresent();
        // Amendment §B.7: the same milestone releases the item's held proceeds.
        proceedsService.releasePhysical(orderItemId);

        return granted;
    }

    // For a refund decision, which holds the Order lock already: the item is locked after it, as in advance().
    @Override
    public Optional<Fulfillment> findLocked(Long orderItemId) {
        return repository.findWithLockByOrderItemId(orderItemId).map(mapper::toModel);
    }

    // F15 R4: an approved refund cancels an item that has not been delivered. The caller holds the Order and item
    // locks and has checked the refund; a delivered item keeps DELIVERED, because DeliveredAt belongs to it.
    @Override
    public Fulfillment cancel(Long staffId, Long orderItemId, String reason) {
        PhysicalFulfillment entity = repository.findWithLockByOrderItemId(orderItemId)
            .orElseThrow(() -> new AppException(AppErrorMessage.FULFILLMENT_NOT_FOUND));
        FulfillmentStatus current = FulfillmentStatus.valueOf(entity.getStatus());

        if (!nextStep.containsKey(current)) {
            throw new IllegalStateException("Fulfillment cannot be cancelled from " + current + ": " + orderItemId);
        }

        OffsetDateTime changedAt = now();
        entity.setStatus(FulfillmentStatus.CANCELLED.name());
        entity.setUpdatedAt(changedAt);
        PhysicalFulfillment saved = repository.saveAndFlush(entity);
        record(orderItemId, current, FulfillmentStatus.CANCELLED, reason, changedAt, staffId);
        log.info("[FULFILLMENT] - ACTION: cancel: staffId: {}: orderItemId: {}: from: {}", staffId, orderItemId,
            current);

        return mapper.toModel(saved);
    }

    @Override
    public OffsetDateTime returnWindowClosesAt(OffsetDateTime deliveredAt) {
        return deliveredAt.plus(properties.returnWindow());
    }

    private Fulfillment begin(Long orderItemId, OffsetDateTime startedAt) {
        Fulfillment fulfillment = Fulfillment.builder()
            .withOrderItemId(orderItemId)
            .withStatus(FulfillmentStatus.PENDING)
            .withUpdatedAt(startedAt)
            .build();
        PhysicalFulfillment saved = repository.saveAndFlush(mapper.toEntity(fulfillment));
        record(orderItemId, null, FulfillmentStatus.PENDING, null, startedAt, null);

        return mapper.toModel(saved);
    }

    private void record(
        Long orderItemId, FulfillmentStatus from, FulfillmentStatus to, String reason, OffsetDateTime occurredAt,
        Long staffId
    ) {
        FulfillmentStep step = FulfillmentStep.builder()
            .withOrderItemId(orderItemId)
            .withFromStatus(from)
            .withToStatus(to)
            .withReason(reason)
            .withOccurredAt(occurredAt)
            .withActorStaffId(staffId)
            .build();
        eventRepository.saveAndFlush(mapper.toEntity(step));
    }

    // Adds what to make and where to send it, from the Order's snapshots. Every fulfillment row belongs to an
    // Order Item (foreign key), so a missing item is a broken invariant, not a client error.
    private Fulfillment describe(Fulfillment fulfillment, ShippableItem item) {
        if (item == null) {
            throw new IllegalStateException("Fulfillment has no Order Item: " + fulfillment.getOrderItemId());
        }

        fulfillment.setOrderId(item.getOrderId());
        fulfillment.setArtworkTitle(item.getArtworkTitle());
        fulfillment.setListingName(item.getListingName());
        fulfillment.setSpecifications(item.getSpecifications());
        fulfillment.setQuantity(item.getQuantity());
        fulfillment.setShippingAddress(item.getShippingAddress());

        return fulfillment;
    }

    // A physical item of an unpaid Order has not started; a digital or unknown item has no fulfillment at all.
    private AppException notStarted(Order order, Long orderItemId) {
        boolean physical = order.getItems().stream()
            .anyMatch(line -> line.getOrderItemId().equals(orderItemId)
                && line.getListingType() == ListingType.PHYSICAL);
        boolean unpaid = order.getStatus() != OrderStatus.PAID;

        return new AppException(physical && unpaid
            ? AppErrorMessage.FULFILLMENT_NOT_STARTED
            : AppErrorMessage.FULFILLMENT_NOT_FOUND);
    }

    private OffsetDateTime windowClosedBefore() {
        return now().minus(properties.returnWindow());
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.strip();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
