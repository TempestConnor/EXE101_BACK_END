package com.exe101.fulfillment.service;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entitlement.model.Entitlement;
import com.exe101.entitlement.service.EntitlementService;
import com.exe101.entity.FulfillmentEvent;
import com.exe101.entity.PhysicalFulfillment;
import com.exe101.fulfillment.config.FulfillmentProperties;
import com.exe101.fulfillment.mapper.FulfillmentEntityMapperImpl;
import com.exe101.fulfillment.model.FulfillmentChange;
import com.exe101.fulfillment.model.FulfillmentStatus;
import com.exe101.fulfillment.repository.FulfillmentEventRepository;
import com.exe101.fulfillment.repository.FulfillmentRepository;
import com.exe101.fulfillment.repository.ReturnRequestRepository;
import com.exe101.listing.model.ListingType;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderLine;
import com.exe101.order.model.OrderStatus;
import com.exe101.order.service.OrderService;
import com.exe101.proceeds.service.ProceedsService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Lenient: each test uses part of the shared persistence.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FulfillmentServiceTest {
    private static long staffId = 7L;
    private static long orderId = 300L;
    private static long digitalItemId = 310L;
    private static long physicalItemId = 311L;
    private static long otherPhysicalItemId = 312L;
    @Mock
    private FulfillmentRepository repository;
    @Mock
    private FulfillmentEventRepository eventRepository;
    @Mock
    private ReturnRequestRepository returnRequestRepository;
    @Mock
    private EntitlementService entitlementService;
    @Mock
    private OrderService orderService;
    @Mock
    private ProceedsService proceedsService;
    private FulfillmentServiceImpl service;
    private List<PhysicalFulfillment> savedFulfillments;
    private List<FulfillmentEvent> savedEvents;

    @BeforeEach
    void setUp() {
        service = new FulfillmentServiceImpl(repository, eventRepository, returnRequestRepository,
            new FulfillmentEntityMapperImpl(), entitlementService, orderService, proceedsService,
            new FulfillmentProperties(Duration.ofHours(48), Duration.ofMinutes(10)));
        savedFulfillments = new ArrayList<>();
        savedEvents = new ArrayList<>();
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            PhysicalFulfillment saved = invocation.getArgument(0);
            savedFulfillments.add(saved);
            return saved;
        });
        when(eventRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            FulfillmentEvent saved = invocation.getArgument(0);
            savedEvents.add(saved);
            return saved;
        });
    }

    @Test
    void start_for_paid_order_ok() {
        // given
        var order = order(OrderStatus.PAID);

        // when
        var started = service.start(order);

        // then
        assertAll(
            () -> assertThat(started).extracting(fulfillment -> fulfillment.getOrderItemId())
                .containsExactly(physicalItemId, otherPhysicalItemId),
            () -> assertThat(savedFulfillments).extracting(PhysicalFulfillment::getStatus).containsOnly("PENDING"),
            () -> assertThat(savedFulfillments).extracting(PhysicalFulfillment::getListingType)
                .containsOnly("PHYSICAL"),
            () -> assertThat(savedFulfillments).extracting(PhysicalFulfillment::getDeliveredAt).containsOnlyNulls(),
            () -> assertThat(savedEvents).extracting(FulfillmentEvent::getOrderItemId)
                .containsExactly(physicalItemId, otherPhysicalItemId),
            () -> assertThat(savedEvents).extracting(FulfillmentEvent::getFromStatus).containsOnlyNulls(),
            () -> assertThat(savedEvents).extracting(FulfillmentEvent::getToStatus).containsOnly("PENDING"),
            () -> assertThat(savedEvents).extracting(FulfillmentEvent::getActorStaffId).containsOnlyNulls()
        );
    }

    @Test
    void start_for_unpaid_order_ko() {
        // given
        var order = order(OrderStatus.PENDING_PAYMENT);

        // when / then
        assertThatThrownBy(() -> service.start(order)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void advance_next_step_ok() {
        // given
        lockedOrder(OrderStatus.PAID);
        var entity = locked(physicalItemId, "PENDING");

        // when
        var updated = service.advance(staffId, physicalItemId, change(FulfillmentStatus.IN_PRODUCTION, null, null));

        // then
        var event = savedEvents.getFirst();
        assertAll(
            () -> assertThat(updated.getStatus()).isEqualTo(FulfillmentStatus.IN_PRODUCTION),
            () -> assertThat(entity.getStatus()).isEqualTo("IN_PRODUCTION"),
            () -> assertThat(entity.getUpdatedAt()).isAfter(now().minusMinutes(1)),
            () -> assertThat(savedEvents).hasSize(1),
            () -> assertThat(event.getOrderItemId()).isEqualTo(physicalItemId),
            () -> assertThat(event.getFromStatus()).isEqualTo("PENDING"),
            () -> assertThat(event.getToStatus()).isEqualTo("IN_PRODUCTION"),
            () -> assertThat(event.getActorStaffId()).isEqualTo(staffId),
            () -> assertThat(event.getReason()).isEqualTo("Sent to the printer")
        );
    }

    @Test
    void advance_unpaid_order_ko() {
        // given
        // Payment never succeeded, so no fulfillment was started.
        lockedOrder(OrderStatus.PENDING_PAYMENT);

        // when / then
        assertThatThrownBy(() -> service.advance(staffId, physicalItemId,
            change(FulfillmentStatus.IN_PRODUCTION, null, null)))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.FULFILLMENT_NOT_STARTED);
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void advance_skipping_step_ko() {
        // given
        lockedOrder(OrderStatus.PAID);
        var entity = locked(physicalItemId, "PENDING");

        // when / then
        assertThatThrownBy(() -> service.advance(staffId, physicalItemId,
            change(FulfillmentStatus.SHIPPED, "VNPost", null)))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.FULFILLMENT_STATUS_CHANGE_NOT_ALLOWED);
        assertThat(entity.getStatus()).isEqualTo("PENDING");
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(value = FulfillmentStatus.class, names = {"PENDING", "IN_PRODUCTION", "SHIPPED", "DELIVERED"})
    void advance_from_terminal_ko(FulfillmentStatus target) {
        // given
        lockedOrder(OrderStatus.PAID);
        lockedDelivered(physicalItemId, now().minusHours(1));

        // when / then
        assertThatThrownBy(() -> service.advance(staffId, physicalItemId, change(target, null, null)))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.FULFILLMENT_STATUS_CHANGE_NOT_ALLOWED);
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(value = FulfillmentStatus.class, names = {"FAILED", "CANCELLED"})
    void advance_to_failed_or_cancelled_ko(FulfillmentStatus target) {
        // given
        lockedOrder(OrderStatus.PAID);
        locked(physicalItemId, "IN_PRODUCTION");

        // when / then
        assertThatThrownBy(() -> service.advance(staffId, physicalItemId, change(target, null, null)))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.FULFILLMENT_STATUS_CHANGE_NOT_ALLOWED);
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void advance_not_found_ko() {
        // given
        when(orderService.findLockedByItem(999L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.advance(staffId, 999L, change(FulfillmentStatus.IN_PRODUCTION, null, null)))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.FULFILLMENT_NOT_FOUND);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void ship_with_carrier_and_tracking_ok() {
        // given
        lockedOrder(OrderStatus.PAID);
        var entity = locked(physicalItemId, "READY_TO_SHIP");

        // when
        var updated = service.advance(staffId, physicalItemId,
            change(FulfillmentStatus.SHIPPED, " VNPost ", "EB123456789VN"));

        // then
        assertAll(
            () -> assertThat(updated.getStatus()).isEqualTo(FulfillmentStatus.SHIPPED),
            () -> assertThat(entity.getCarrier()).isEqualTo("VNPost"),
            () -> assertThat(entity.getTrackingReference()).isEqualTo("EB123456789VN"),
            () -> assertThat(entity.getDeliveredAt()).isNull(),
            () -> assertThat(savedEvents).extracting(FulfillmentEvent::getToStatus).containsExactly("SHIPPED")
        );
    }

    @Test
    void ship_tracking_without_carrier_ko() {
        // given
        lockedOrder(OrderStatus.PAID);
        locked(physicalItemId, "READY_TO_SHIP");

        // when / then
        assertThatThrownBy(() -> service.advance(staffId, physicalItemId,
            change(FulfillmentStatus.SHIPPED, " ", "EB123456789VN")))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.FULFILLMENT_SHIPMENT_INVALID);
        verifyNoInteractions(orderService);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void carrier_outside_shipped_ko() {
        // given
        lockedOrder(OrderStatus.PAID);
        locked(physicalItemId, "PENDING");

        // when / then
        assertThatThrownBy(() -> service.advance(staffId, physicalItemId,
            change(FulfillmentStatus.IN_PRODUCTION, "VNPost", null)))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.FULFILLMENT_SHIPMENT_INVALID);
        verifyNoInteractions(orderService);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void deliver_sets_delivered_at_ok() {
        // given
        lockedOrder(OrderStatus.PAID);
        var entity = locked(physicalItemId, "SHIPPED");
        var before = now();

        // when
        var updated = service.advance(staffId, physicalItemId, change(FulfillmentStatus.DELIVERED, null, null));

        // then
        assertAll(
            () -> assertThat(updated.getStatus()).isEqualTo(FulfillmentStatus.DELIVERED),
            () -> assertThat(entity.getDeliveredAt()).isAfterOrEqualTo(before),
            () -> assertThat(entity.getDeliveredAt()).isBeforeOrEqualTo(now()),
            () -> assertThat(entity.getUpdatedAt()).isEqualTo(entity.getDeliveredAt())
        );
        verifyNoInteractions(entitlementService);
    }

    @Test
    void deliver_one_of_two_ok() {
        // given
        lockedOrder(OrderStatus.PAID);
        var delivered = locked(physicalItemId, "SHIPPED");
        var other = locked(otherPhysicalItemId, "SHIPPED");

        // when
        service.advance(staffId, physicalItemId, change(FulfillmentStatus.DELIVERED, null, null));

        // then
        assertAll(
            () -> assertThat(delivered.getStatus()).isEqualTo("DELIVERED"),
            () -> assertThat(other.getStatus()).isEqualTo("SHIPPED"),
            () -> assertThat(other.getDeliveredAt()).isNull(),
            () -> assertThat(savedFulfillments).containsExactly(delivered),
            () -> assertThat(savedEvents).extracting(FulfillmentEvent::getOrderItemId).containsExactly(physicalItemId)
        );
    }

    @Test
    void window_closed_grants_ok() {
        // given
        var order = lockedOrder(OrderStatus.PAID);
        lockedDelivered(physicalItemId, now().minusHours(48).minusMinutes(1));
        when(entitlementService.grantIncluded(order, physicalItemId)).thenReturn(Optional.of(new Entitlement()));

        // when
        var granted = service.grantIfWindowClosed(physicalItemId);

        // then
        assertThat(granted).isTrue();
        verify(entitlementService).grantIncluded(order, physicalItemId);
    }

    @Test
    void window_open_withholds_ok() {
        // given
        lockedOrder(OrderStatus.PAID);
        lockedDelivered(physicalItemId, now().minusHours(47));

        // when
        var granted = service.grantIfWindowClosed(physicalItemId);

        // then
        assertThat(granted).isFalse();
        verifyNoInteractions(entitlementService);
    }

    @Test
    void open_refund_request_withholds_ok() {
        // given
        lockedOrder(OrderStatus.PAID);
        lockedDelivered(physicalItemId, now().minusHours(49));
        when(returnRequestRepository.existsByOrderItemIdAndStatusNot(physicalItemId, "REJECTED")).thenReturn(true);

        // when
        var granted = service.grantIfWindowClosed(physicalItemId);

        // then
        assertThat(granted).isFalse();
        verifyNoInteractions(entitlementService);
    }

    @Test
    void rejected_refund_request_grants_ok() {
        // given
        // Only requests in a status other than REJECTED withhold the files.
        var order = lockedOrder(OrderStatus.PAID);
        lockedDelivered(physicalItemId, now().minusHours(49));
        when(returnRequestRepository.existsByOrderItemIdAndStatusNot(anyLong(), any())).thenReturn(true);
        when(returnRequestRepository.existsByOrderItemIdAndStatusNot(physicalItemId, "REJECTED")).thenReturn(false);
        when(entitlementService.grantIncluded(order, physicalItemId)).thenReturn(Optional.of(new Entitlement()));

        // when
        var granted = service.grantIfWindowClosed(physicalItemId);

        // then
        assertThat(granted).isTrue();
        verify(entitlementService).grantIncluded(order, physicalItemId);
    }

    @Test
    void physical_proceeds_eligible_when_window_closes_ok() {
        // given
        var order = lockedOrder(OrderStatus.PAID);
        lockedDelivered(physicalItemId, now().minusHours(48).minusMinutes(1));
        when(entitlementService.grantIncluded(order, physicalItemId)).thenReturn(Optional.of(new Entitlement()));

        // when
        var granted = service.grantIfWindowClosed(physicalItemId);

        // then
        assertThat(granted).isTrue();
        verify(entitlementService).grantIncluded(order, physicalItemId);
        verify(proceedsService).releasePhysical(physicalItemId);
    }

    @Test
    void physical_proceeds_held_while_return_open_ko() {
        // given
        lockedOrder(OrderStatus.PAID);
        lockedDelivered(physicalItemId, now().minusHours(49));
        when(returnRequestRepository.existsByOrderItemIdAndStatusNot(physicalItemId, "REJECTED")).thenReturn(true);

        // when
        service.grantIfWindowClosed(physicalItemId);

        // then
        verifyNoInteractions(proceedsService);
    }

    @Test
    void physical_proceeds_held_before_window_closes_ko() {
        // given
        lockedOrder(OrderStatus.PAID);
        lockedDelivered(physicalItemId, now().minusHours(47));

        // when
        service.grantIfWindowClosed(physicalItemId);

        // then
        verifyNoInteractions(proceedsService);
    }

    private Order lockedOrder(OrderStatus status) {
        var order = order(status);
        when(orderService.findLockedByItem(anyLong())).thenReturn(Optional.of(order));

        return order;
    }

    private PhysicalFulfillment locked(long orderItemId, String status) {
        var entity = new PhysicalFulfillment(orderItemId, "PHYSICAL", status, null, null, null, now().minusDays(1),
            new byte[] {0, 0, 0, 0, 0, 0, 0, 1});
        when(repository.findWithLockByOrderItemId(orderItemId)).thenReturn(Optional.of(entity));

        return entity;
    }

    private PhysicalFulfillment lockedDelivered(long orderItemId, OffsetDateTime deliveredAt) {
        var entity = locked(orderItemId, "DELIVERED");
        entity.setDeliveredAt(deliveredAt);
        entity.setUpdatedAt(deliveredAt);

        return entity;
    }

    private FulfillmentChange change(FulfillmentStatus status, String carrier, String trackingReference) {
        return FulfillmentChange.builder()
            .withStatus(status)
            .withCarrier(carrier)
            .withTrackingReference(trackingReference)
            .withReason("Sent to the printer")
            .build();
    }

    private Order order(OrderStatus status) {
        return Order.builder()
            .withOrderId(orderId)
            .withCustomerId(42L)
            .withStatus(status)
            .withItems(List.of(line(digitalItemId, ListingType.DIGITAL), line(physicalItemId, ListingType.PHYSICAL),
                line(otherPhysicalItemId, ListingType.PHYSICAL)))
            .build();
    }

    private OrderLine line(long orderItemId, ListingType listingType) {
        return OrderLine.builder()
            .withOrderItemId(orderItemId)
            .withListingType(listingType)
            .withQuantity(1)
            .withAssets(List.of())
            .build();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
