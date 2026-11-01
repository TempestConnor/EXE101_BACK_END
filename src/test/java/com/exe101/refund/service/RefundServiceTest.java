package com.exe101.refund.service;

import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entitlement.service.EntitlementService;
import com.exe101.entity.RefundRequest;
import com.exe101.entity.RefundTransfer;
import com.exe101.entity.ShippingRefund;
import com.exe101.fulfillment.model.Fulfillment;
import com.exe101.fulfillment.model.FulfillmentStatus;
import com.exe101.fulfillment.service.FulfillmentService;
import com.exe101.ledger.model.RefundMovement;
import com.exe101.ledger.service.LedgerService;
import com.exe101.listing.model.ListingType;
import com.exe101.order.model.ItemFulfillment;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderLine;
import com.exe101.order.model.OrderStatus;
import com.exe101.order.model.PaymentStatus;
import com.exe101.order.model.PaymentTarget;
import com.exe101.order.service.OrderService;
import com.exe101.proceeds.model.Proceeds;
import com.exe101.proceeds.model.ProceedsStatus;
import com.exe101.proceeds.service.ProceedsService;
import com.exe101.refund.mapper.RefundEntityMapperImpl;
import com.exe101.refund.model.RefundDecision;
import com.exe101.refund.model.RefundStatus;
import com.exe101.refund.model.RefundVerdict;
import com.exe101.refund.model.TransferOutcome;
import com.exe101.refund.model.TransferStatus;
import com.exe101.refund.repository.RefundRequestRepository;
import com.exe101.refund.repository.RefundTransferRepository;
import com.exe101.refund.repository.ShippingRefundRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Lenient: each test uses part of the shared fixture. Two prints of 110 000 (80 000 cost + 30 000 markup) with
// 11 000 tax make a refund of 231 000; the Order's shipping is 20 000.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundServiceTest {
    private static long buyerId = 42L;
    private static long staffId = 7L;
    private static long orderId = 300L;
    private static long itemId = 310L;
    private static long otherItemId = 311L;
    private static long artistId = 21L;
    private static long paymentId = 400L;
    private static long refundRequestId = 500L;
    private static long transferId = 600L;
    @Mock
    private RefundRequestRepository repository;
    @Mock
    private RefundTransferRepository transferRepository;
    @Mock
    private ShippingRefundRepository shippingRepository;
    @Mock
    private OrderService orderService;
    @Mock
    private FulfillmentService fulfillmentService;
    @Mock
    private ProceedsService proceedsService;
    @Mock
    private EntitlementService entitlementService;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private AuditService auditService;
    private RefundServiceImpl service;
    private List<RefundRequest> savedRequests;
    private List<RefundTransfer> savedTransfers;
    private List<ShippingRefund> savedShipping;

    @BeforeEach
    void setUp() {
        service = new RefundServiceImpl(repository, transferRepository, shippingRepository,
            new RefundEntityMapperImpl(), orderService, fulfillmentService, proceedsService, entitlementService,
            ledgerService, auditService);
        savedRequests = new ArrayList<>();
        savedTransfers = new ArrayList<>();
        savedShipping = new ArrayList<>();
        var ids = new AtomicLong(1000);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            RefundRequest saved = invocation.getArgument(0);
            if (saved.getRefundRequestId() == null) {
                saved.setRefundRequestId(ids.incrementAndGet());
            }
            savedRequests.add(saved);
            return saved;
        });
        when(transferRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            RefundTransfer saved = invocation.getArgument(0);
            if (saved.getRefundTransferId() == null) {
                saved.setRefundTransferId(ids.incrementAndGet());
            }
            savedTransfers.add(saved);
            return saved;
        });
        when(shippingRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ShippingRefund saved = invocation.getArgument(0);
            savedShipping.add(saved);
            return saved;
        });
        when(transferRepository.findByRefundRequestIdIn(anyCollection())).thenAnswer(invocation -> savedTransfers);
        when(shippingRepository.findByRefundRequestIdIn(anyCollection())).thenAnswer(invocation -> savedShipping);
        when(transferRepository.sumAmountByPaymentId(anyLong())).thenReturn(BigDecimal.ZERO);
        when(fulfillmentService.returnWindowClosesAt(any())).thenAnswer(invocation ->
            invocation.<OffsetDateTime>getArgument(0).plusHours(48));
        when(proceedsService.voidHeld(anyLong())).thenAnswer(invocation -> proceeds(ProceedsStatus.VOID));
    }

    @Test
    void request_refund_physical_before_delivery_ok() {
        // given
        givenOrder(order(OrderStatus.PAID, physical(itemId, "SHIPPED", null), physical(otherItemId, "SHIPPED", null)));

        // when
        var refund = service.request(buyerId, orderId, itemId, " Print arrived torn ");

        // then
        var saved = savedRequests.getFirst();
        assertAll(
            () -> assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED),
            () -> assertThat(saved.getOrderItemId()).isEqualTo(itemId),
            () -> assertThat(saved.getCommissionId()).isNull(),
            () -> assertThat(saved.getRequestedByUserId()).isEqualTo(buyerId),
            () -> assertThat(saved.getReason()).isEqualTo("Print arrived torn"),
            () -> assertThat(saved.getStatus()).isEqualTo("REQUESTED"),
            () -> assertThat(saved.getRequestedAt()).isNotNull(),
            () -> assertThat(saved.getDecidedAt()).isNull()
        );
    }

    @Test
    void request_refund_delivered_within_return_window_ok() {
        // given
        givenOrder(order(OrderStatus.PAID, physical(itemId, "DELIVERED", now().minusHours(47))));

        // when
        var refund = service.request(buyerId, orderId, itemId, "Colours do not match the listing");

        // then
        assertAll(
            () -> assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED),
            () -> assertThat(savedRequests).hasSize(1)
        );
    }

    @Test
    void request_refund_delivered_after_return_window_ko() {
        // given
        givenOrder(order(OrderStatus.PAID, physical(itemId, "DELIVERED", now().minusHours(49))));

        // when / then
        assertRejected(() -> service.request(buyerId, orderId, itemId, "Too late"),
            AppErrorMessage.REFUND_NOT_ALLOWED);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void request_refund_files_already_granted_ko() {
        // given
        givenOrder(order(OrderStatus.PAID, physical(itemId, "DELIVERED", now().minusHours(49))));
        when(entitlementService.isGranted(itemId)).thenReturn(true);

        // when / then
        assertRejected(() -> service.request(buyerId, orderId, itemId, "Granted already"),
            AppErrorMessage.REFUND_NOT_ALLOWED);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void request_refund_digital_item_ko() {
        // given
        givenOrder(order(OrderStatus.PAID, digital(itemId)));

        // when / then
        assertRejected(() -> service.request(buyerId, orderId, itemId, "Changed my mind"),
            AppErrorMessage.REFUND_NOT_ALLOWED);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void request_refund_unpaid_order_ko() {
        // given
        givenOrder(order(OrderStatus.PENDING_PAYMENT, physical(itemId, null, null)));

        // when / then
        assertRejected(() -> service.request(buyerId, orderId, itemId, "Not paid yet"),
            AppErrorMessage.REFUND_NOT_ALLOWED);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void request_refund_item_not_found_ko() {
        // given
        // Unknown item, and an item of another Customer's Order: both look the same to the caller.
        when(orderService.findLockedByItem(999L)).thenReturn(Optional.empty());
        givenOrder(order(OrderStatus.PAID, physical(itemId, "SHIPPED", null)));

        // when / then
        assertRejected(() -> service.request(buyerId, orderId, 999L, "Missing"),
            AppErrorMessage.ORDER_ITEM_NOT_FOUND);
        assertRejected(() -> service.request(43L, orderId, itemId, "Not mine"), AppErrorMessage.ORDER_ITEM_NOT_FOUND);
        assertRejected(() -> service.request(buyerId, 301L, itemId, "Wrong Order"),
            AppErrorMessage.ORDER_ITEM_NOT_FOUND);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void request_refund_open_request_exists_ko() {
        // given
        givenOrder(order(OrderStatus.PAID, physical(itemId, "SHIPPED", null)));
        when(repository.existsByOrderItemIdAndStatusIn(eq(itemId), anyCollection())).thenReturn(true);

        // when / then
        assertRejected(() -> service.request(buyerId, orderId, itemId, "Again"), AppErrorMessage.REFUND_REQUEST_OPEN);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void approve_refund_undelivered_item_ok() {
        // given
        givenRequest(order(OrderStatus.PAID, physical(itemId, "SHIPPED", null), physical(otherItemId, "SHIPPED", null)),
            "SHIPPED", null, now().minusHours(1));

        // when
        var refund = service.decide(staffId, refundRequestId, approve("Damaged in transit"));

        // then
        var transfer = savedTransfers.getFirst();
        var audit = auditEntry();
        var movement = approvedMovement();
        assertAll(
            () -> assertThat(refund.getStatus()).isEqualTo(RefundStatus.APPROVED),
            () -> assertThat(refund.getDecidedByStaffId()).isEqualTo(staffId),
            () -> assertThat(refund.getDecisionReason()).isEqualTo("Damaged in transit"),
            () -> assertThat(refund.getDecidedAt()).isNotNull(),
            () -> verify(fulfillmentService).cancel(eq(staffId), eq(itemId), anyString()),
            () -> verify(proceedsService).voidHeld(itemId),
            () -> assertThat(savedTransfers).hasSize(1),
            () -> assertThat(transfer.getStatus()).isEqualTo("PENDING"),
            () -> assertThat(transfer.getPaymentId()).isEqualTo(paymentId),
            () -> assertThat(transfer.getAmount()).isEqualByComparingTo("231000"),
            () -> assertThat(transfer.getCurrencyCode()).isEqualTo("VND"),
            () -> assertThat(transfer.getProviderRefundRef()).isNull(),
            () -> assertThat(refund.getTransfer().getAmount()).isEqualByComparingTo("231000"),
            () -> assertThat(movement.getProceeds()).isEqualByComparingTo("60000"),
            () -> assertThat(movement.getManufacturing()).isEqualByComparingTo("160000"),
            () -> assertThat(movement.getTax()).isEqualByComparingTo("11000"),
            () -> assertThat(movement.getShipping()).isEqualByComparingTo("0"),
            () -> assertThat(movement.getArtistId()).isEqualTo(artistId),
            () -> assertThat(audit.getActionType()).isEqualTo("REFUND_APPROVED"),
            () -> assertThat(audit.getTargetType()).isEqualTo("REFUND_REQUEST"),
            () -> assertThat(audit.getTargetId()).isEqualTo(refundRequestId),
            () -> assertThat(audit.getStaffId()).isEqualTo(staffId),
            () -> assertThat(audit.getReason()).isEqualTo("Damaged in transit"),
            () -> assertThat(audit.getDetails()).contains("amount=231000")
        );
    }

    @Test
    void approve_refund_delivered_return_ok() {
        // given
        // Delivered 47 hours ago and asked about an hour after delivery, inside the window.
        var deliveredAt = now().minusHours(47);
        givenRequest(order(OrderStatus.PAID, physical(itemId, "DELIVERED", deliveredAt),
            physical(otherItemId, "SHIPPED", null)), "DELIVERED", deliveredAt, deliveredAt.plusHours(1));

        // when
        var refund = service.decide(staffId, refundRequestId, approve(null));

        // then
        assertAll(
            () -> assertThat(refund.getStatus()).isEqualTo(RefundStatus.APPROVED),
            () -> verify(fulfillmentService, never()).cancel(anyLong(), anyLong(), any()),
            () -> verify(proceedsService).voidHeld(itemId),
            () -> assertThat(savedTransfers.getFirst().getAmount()).isEqualByComparingTo("231000")
        );
    }

    @Test
    void approve_refund_request_not_found_ko() {
        // given
        when(repository.findOrderItemIdByRefundRequestId(999L)).thenReturn(Optional.empty());

        // when / then
        assertRejected(() -> service.decide(staffId, 999L, approve(null)), AppErrorMessage.REFUND_REQUEST_NOT_FOUND);
        verifyNoInteractions(orderService, auditService, ledgerService);
    }

    @Test
    void approve_refund_already_decided_ko() {
        // given
        givenRequest(order(OrderStatus.PAID, physical(itemId, "SHIPPED", null)), "SHIPPED", null,
            now().minusHours(1));
        request().setStatus("REJECTED");

        // when / then
        assertRejected(() -> service.decide(staffId, refundRequestId, approve(null)),
            AppErrorMessage.REFUND_ALREADY_DECIDED);
        assertRejected(() -> service.decide(staffId, refundRequestId, reject("Again")),
            AppErrorMessage.REFUND_ALREADY_DECIDED);
        assertNothingWritten();
    }

    @Test
    void approve_refund_proceeds_released_ko() {
        // given
        givenRequest(order(OrderStatus.PAID, physical(itemId, "SHIPPED", null)), "SHIPPED", null,
            now().minusHours(1));
        when(proceedsService.findLockedByOrderItem(itemId)).thenReturn(Optional.of(proceeds(ProceedsStatus.RESERVED)));

        // when / then
        assertRejected(() -> service.decide(staffId, refundRequestId, approve(null)),
            AppErrorMessage.REFUND_NOT_ALLOWED);
        assertNothingWritten();
    }

    @Test
    void approve_first_of_two_physical_items_no_shipping_ok() {
        // given
        givenRequest(order(OrderStatus.PAID, physical(itemId, "SHIPPED", null), physical(otherItemId, "SHIPPED", null)),
            "SHIPPED", null, now().minusHours(1));
        when(repository.findByOrderItemIdInAndStatusIn(anyCollection(), anyCollection()))
            .thenAnswer(invocation -> List.of(request()));

        // when
        var refund = service.decide(staffId, refundRequestId, approve(null));

        // then
        assertAll(
            () -> assertThat(savedShipping).isEmpty(),
            () -> assertThat(refund.getShippingAmount()).isNull(),
            () -> assertThat(savedTransfers.getFirst().getAmount()).isEqualByComparingTo("231000"),
            () -> assertThat(approvedMovement().getShipping()).isEqualByComparingTo("0")
        );
    }

    @Test
    void approve_last_physical_item_adds_shipping_once_ok() {
        // given
        givenRequest(order(OrderStatus.PAID, physical(itemId, "SHIPPED", null), physical(otherItemId, "SHIPPED", null)),
            "SHIPPED", null, now().minusHours(1));
        var sibling = new RefundRequest(otherItemId, null, buyerId, "First print", "REFUNDED", now().minusHours(3),
            now().minusHours(2), staffId, null);
        when(repository.findByOrderItemIdInAndStatusIn(anyCollection(), anyCollection()))
            .thenAnswer(invocation -> List.of(sibling, request()));

        // when
        var refund = service.decide(staffId, refundRequestId, approve(null));

        // then
        var shipping = savedShipping.getFirst();
        assertAll(
            () -> assertThat(savedShipping).hasSize(1),
            () -> assertThat(shipping.getOrderId()).isEqualTo(orderId),
            () -> assertThat(shipping.getRefundRequestId()).isEqualTo(refundRequestId),
            () -> assertThat(shipping.getAmount()).isEqualByComparingTo("20000"),
            () -> assertThat(refund.getShippingAmount()).isEqualByComparingTo("20000"),
            () -> assertThat(savedTransfers.getFirst().getAmount()).isEqualByComparingTo("251000"),
            () -> assertThat(approvedMovement().getShipping()).isEqualByComparingTo("20000"),
            () -> assertThat(approvedMovement().getAmount()).isEqualByComparingTo("251000")
        );
    }

    @Test
    void approve_only_physical_item_of_mixed_order_refunds_shipping_ok() {
        // given
        givenRequest(order(OrderStatus.PAID, digital(otherItemId), physical(itemId, "IN_PRODUCTION", null)),
            "IN_PRODUCTION", null, now().minusHours(1));
        when(repository.findByOrderItemIdInAndStatusIn(anyCollection(), anyCollection()))
            .thenAnswer(invocation -> List.of(request()));

        // when
        var refund = service.decide(staffId, refundRequestId, approve(null));

        // then
        assertAll(
            () -> assertThat(savedShipping).hasSize(1),
            () -> assertThat(refund.getShippingAmount()).isEqualByComparingTo("20000"),
            () -> assertThat(savedTransfers.getFirst().getAmount()).isEqualByComparingTo("251000")
        );
    }

    @Test
    void reject_refund_ok() {
        // given
        givenRequest(order(OrderStatus.PAID, physical(itemId, "SHIPPED", null)), "SHIPPED", null,
            now().minusHours(1));

        // when
        var refund = service.decide(staffId, refundRequestId, reject(" Damage caused by the buyer "));

        // then
        var audit = auditEntry();
        assertAll(
            () -> assertThat(refund.getStatus()).isEqualTo(RefundStatus.REJECTED),
            () -> assertThat(refund.getDecisionReason()).isEqualTo("Damage caused by the buyer"),
            () -> assertThat(refund.getDecidedByStaffId()).isEqualTo(staffId),
            () -> assertThat(refund.getDecidedAt()).isNotNull(),
            () -> assertThat(audit.getActionType()).isEqualTo("REFUND_REJECTED"),
            () -> assertThat(audit.getTargetId()).isEqualTo(refundRequestId),
            () -> assertThat(audit.getReason()).isEqualTo("Damage caused by the buyer"),
            () -> verify(proceedsService, never()).voidHeld(anyLong()),
            () -> verify(fulfillmentService, never()).cancel(anyLong(), anyLong(), any()),
            () -> assertThat(savedTransfers).isEmpty(),
            () -> verifyNoInteractions(ledgerService)
        );
    }

    @Test
    void reject_refund_without_reason_ko() {
        // given
        var decision = reject("  ");

        // when / then
        assertRejected(() -> service.decide(staffId, refundRequestId, decision),
            AppErrorMessage.REFUND_DECISION_REASON_REQUIRED);
        verifyNoInteractions(repository, orderService, auditService);
    }

    @Test
    void record_transfer_succeeded_ok() {
        // given
        givenTransfer("PENDING");

        // when
        var refund = service.recordTransferOutcome(staffId, transferId, outcome(TransferStatus.SUCCEEDED,
            " VCB-20261101-0042 "));

        // then
        var transfer = savedTransfers.getFirst();
        var paid = ArgumentCaptor.forClass(RefundMovement.class);
        verify(ledgerService).recordRefundPaid(paid.capture());
        assertAll(
            () -> verify(ledgerService).requireReconciled(),
            () -> assertThat(transfer.getStatus()).isEqualTo("SUCCEEDED"),
            () -> assertThat(transfer.getProviderName()).isEqualTo("MANUAL"),
            () -> assertThat(transfer.getProviderRefundRef()).isEqualTo("VCB-20261101-0042"),
            () -> assertThat(transfer.getSucceededAt()).isNotNull(),
            () -> assertThat(refund.getStatus()).isEqualTo(RefundStatus.REFUNDED),
            () -> assertThat(refund.getTransfer().getTransferReference()).isEqualTo("VCB-20261101-0042"),
            () -> assertThat(paid.getValue().getSourceId()).isEqualTo(transferId),
            () -> assertThat(paid.getValue().getAmount()).isEqualByComparingTo("231000"),
            () -> assertThat(paid.getValue().getOrderItemId()).isEqualTo(itemId),
            () -> assertThat(auditEntry().getActionType()).isEqualTo("REFUND_TRANSFER_STATUS_CHANGED"),
            () -> assertThat(auditEntry().getTargetType()).isEqualTo("REFUND_TRANSFER")
        );
    }

    @Test
    void record_transfer_succeeded_without_reference_ko() {
        // given
        givenTransfer("PENDING");

        // when / then
        assertRejected(() -> service.recordTransferOutcome(staffId, transferId, outcome(TransferStatus.SUCCEEDED,
            " ")), AppErrorMessage.REFUND_REFERENCE_INVALID);
        assertRejected(() -> service.recordTransferOutcome(staffId, transferId, outcome(TransferStatus.FAILED,
            "VCB-1")), AppErrorMessage.REFUND_REFERENCE_INVALID);
        verify(transferRepository, never()).findWithLockByRefundTransferId(anyLong());
        verifyNoInteractions(ledgerService, auditService);
    }

    @Test
    void record_transfer_failed_ok() {
        // given
        givenTransfer("PENDING");

        // when
        var refund = service.recordTransferOutcome(staffId, transferId, outcome(TransferStatus.FAILED, null));

        // then
        assertAll(
            () -> assertThat(savedTransfers.getFirst().getStatus()).isEqualTo("FAILED"),
            () -> assertThat(savedTransfers.getFirst().getProviderRefundRef()).isNull(),
            () -> assertThat(refund.getStatus()).isEqualTo(RefundStatus.APPROVED),
            () -> verify(ledgerService, never()).requireReconciled(),
            () -> verify(ledgerService, never()).recordRefundPaid(any()),
            () -> assertThat(auditEntry().getDetails()).contains("from=PENDING;to=FAILED")
        );
    }

    @Test
    void record_transfer_retry_after_failure_ok() {
        // given
        givenTransfer("FAILED");

        // when
        service.recordTransferOutcome(staffId, transferId, outcome(TransferStatus.PENDING, null));
        var refund = service.recordTransferOutcome(staffId, transferId, outcome(TransferStatus.SUCCEEDED, "VCB-2"));

        // then
        assertAll(
            () -> assertThat(savedTransfers).extracting(RefundTransfer::getRefundTransferId).containsOnly(transferId),
            () -> assertThat(savedTransfers.getLast().getStatus()).isEqualTo("SUCCEEDED"),
            () -> assertThat(refund.getStatus()).isEqualTo(RefundStatus.REFUNDED),
            () -> verify(ledgerService).recordRefundPaid(any())
        );
    }

    @Test
    void record_transfer_already_succeeded_ko() {
        // given
        givenTransfer("SUCCEEDED");

        // when / then
        assertRejected(() -> service.recordTransferOutcome(staffId, transferId, outcome(TransferStatus.FAILED, null)),
            AppErrorMessage.REFUND_TRANSFER_STATUS_CHANGE_NOT_ALLOWED);
        assertRejected(() -> service.recordTransferOutcome(staffId, transferId, outcome(TransferStatus.SUCCEEDED,
            "VCB-3")), AppErrorMessage.REFUND_TRANSFER_STATUS_CHANGE_NOT_ALLOWED);
        assertThat(savedTransfers).isEmpty();
        verify(ledgerService, never()).recordRefundPaid(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void record_transfer_reconciliation_mismatch_ko() {
        // given
        givenTransfer("PENDING");
        doThrow(new AppException(AppErrorMessage.RECONCILIATION_MISMATCH)).when(ledgerService).requireReconciled();

        // when / then
        assertRejected(() -> service.recordTransferOutcome(staffId, transferId, outcome(TransferStatus.SUCCEEDED,
            "VCB-4")), AppErrorMessage.RECONCILIATION_MISMATCH);
        verify(transferRepository, never()).findWithLockByRefundTransferId(anyLong());
        assertThat(savedTransfers).isEmpty();
        verifyNoInteractions(auditService);
    }

    @Test
    void list_my_refund_requests_ok() {
        // given
        var newer = new RefundRequest(itemId, null, buyerId, "Torn", "APPROVED", now().minusHours(1),
            now(), staffId, null);
        newer.setRefundRequestId(502L);
        var older = new RefundRequest(otherItemId, null, buyerId, "Wrong size", "REJECTED", now().minusHours(5),
            now().minusHours(4), staffId, "Matches the listing");
        older.setRefundRequestId(501L);
        when(repository.findByRequestedByUserIdOrderByRequestedAtDescRefundRequestIdDesc(buyerId))
            .thenReturn(List.of(newer, older));
        savedTransfers.add(transfer(502L, "PENDING"));

        // when
        var refunds = service.listOwn(buyerId);

        // then
        assertAll(
            () -> assertThat(refunds).extracting(refund -> refund.getRefundRequestId()).containsExactly(502L, 501L),
            () -> assertThat(refunds.getFirst().getTransfer().getTransferId()).isEqualTo(transferId),
            () -> assertThat(refunds.get(1).getTransfer()).isNull(),
            () -> assertThat(refunds.get(1).getDecisionReason()).isEqualTo("Matches the listing")
        );
    }

    private void givenOrder(Order order) {
        order.getItems().forEach(line -> when(orderService.findLockedByItem(line.getOrderItemId()))
            .thenReturn(Optional.of(order)));
    }

    // A REQUESTED request for the item, with its fulfillment and held proceeds as the locks would find them.
    private void givenRequest(
        Order order, String fulfillmentStatus, OffsetDateTime deliveredAt, OffsetDateTime requestedAt
    ) {
        givenOrder(order);
        var entity = new RefundRequest(itemId, null, buyerId, "Torn", "REQUESTED", requestedAt, null, null, null);
        entity.setRefundRequestId(refundRequestId);
        when(repository.findOrderItemIdByRefundRequestId(refundRequestId)).thenReturn(Optional.of(itemId));
        when(repository.findWithLockByRefundRequestId(refundRequestId)).thenReturn(Optional.of(entity));
        when(fulfillmentService.findLocked(itemId)).thenReturn(Optional.of(Fulfillment.builder()
            .withOrderItemId(itemId)
            .withStatus(FulfillmentStatus.valueOf(fulfillmentStatus))
            .withDeliveredAt(deliveredAt)
            .build()));
        when(proceedsService.findLockedByOrderItem(itemId)).thenReturn(Optional.of(proceeds(ProceedsStatus.HELD)));
    }

    // An approved request whose transfer of 231 000 is in the given state.
    private void givenTransfer(String status) {
        var entity = new RefundRequest(itemId, null, buyerId, "Torn", "APPROVED", now().minusHours(2),
            now().minusHours(1), staffId, null);
        entity.setRefundRequestId(refundRequestId);
        var transfer = transfer(refundRequestId, status);
        when(transferRepository.findWithLockByRefundTransferId(transferId)).thenReturn(Optional.of(transfer));
        when(repository.findWithLockByRefundRequestId(refundRequestId)).thenReturn(Optional.of(entity));
        when(transferRepository.findByRefundRequestIdIn(anyCollection())).thenReturn(List.of(transfer));
    }

    private RefundTransfer transfer(long requestId, String status) {
        var transfer = new RefundTransfer(requestId, paymentId, new BigDecimal("231000.0000"), "VND", status,
            now().minusHours(1));
        transfer.setRefundTransferId(transferId);

        return transfer;
    }

    private RefundRequest request() {
        return repository.findWithLockByRefundRequestId(refundRequestId).orElseThrow();
    }

    private Order order(OrderStatus status, OrderLine... lines) {
        return Order.builder()
            .withOrderId(orderId)
            .withCustomerId(buyerId)
            .withStatus(status)
            .withCurrencyCode("VND")
            .withShippingAmount(new BigDecimal("20000.0000"))
            .withItems(List.of(lines))
            .withPayment(PaymentTarget.builder()
                .withPaymentId(paymentId)
                .withOrderId(orderId)
                .withAmount(new BigDecimal("1000000.0000"))
                .withCurrencyCode("VND")
                .withStatus(status == OrderStatus.PAID ? PaymentStatus.SUCCEEDED : PaymentStatus.PENDING)
                .build())
            .build();
    }

    private OrderLine physical(long orderItemId, String fulfillmentStatus, OffsetDateTime deliveredAt) {
        var fulfillment = fulfillmentStatus == null ? null : ItemFulfillment.builder()
            .withStatus(fulfillmentStatus)
            .withDeliveredAt(deliveredAt)
            .build();

        return OrderLine.builder()
            .withOrderItemId(orderItemId)
            .withListingType(ListingType.PHYSICAL)
            .withArtistId(artistId)
            .withQuantity(2)
            .withCurrencyCode("VND")
            .withUnitPrice(new BigDecimal("110000.0000"))
            .withManufacturingCostPerUnit(new BigDecimal("80000.0000"))
            .withMarkupPerUnit(new BigDecimal("30000.0000"))
            .withArtistProceeds(new BigDecimal("60000.0000"))
            .withTaxAmount(new BigDecimal("11000.0000"))
            .withLineSubtotal(new BigDecimal("220000.0000"))
            .withFulfillment(fulfillment)
            .build();
    }

    private OrderLine digital(long orderItemId) {
        return OrderLine.builder()
            .withOrderItemId(orderItemId)
            .withListingType(ListingType.DIGITAL)
            .withArtistId(artistId)
            .withQuantity(1)
            .withCurrencyCode("VND")
            .withUnitPrice(new BigDecimal("50000.0000"))
            .withArtistProceeds(new BigDecimal("50000.0000"))
            .withTaxAmount(new BigDecimal("0.0000"))
            .withLineSubtotal(new BigDecimal("50000.0000"))
            .build();
    }

    private Proceeds proceeds(ProceedsStatus status) {
        return Proceeds.builder()
            .withProceedsId(700L)
            .withArtistId(artistId)
            .withOrderItemId(itemId)
            .withAmount(new BigDecimal("60000.0000"))
            .withCurrencyCode("VND")
            .withStatus(status)
            .build();
    }

    private RefundDecision approve(String reason) {
        return RefundDecision.builder().withVerdict(RefundVerdict.APPROVE).withReason(reason).build();
    }

    private RefundDecision reject(String reason) {
        return RefundDecision.builder().withVerdict(RefundVerdict.REJECT).withReason(reason).build();
    }

    private TransferOutcome outcome(TransferStatus status, String reference) {
        return TransferOutcome.builder().withStatus(status).withTransferReference(reference).build();
    }

    private RefundMovement approvedMovement() {
        var movement = ArgumentCaptor.forClass(RefundMovement.class);
        verify(ledgerService).recordRefundApproved(movement.capture());

        return movement.getValue();
    }

    private AuditEntry auditEntry() {
        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService, atLeastOnce()).record(entry.capture());

        return entry.getValue();
    }

    private void assertNothingWritten() {
        verify(repository, never()).saveAndFlush(any());
        verify(fulfillmentService, never()).cancel(anyLong(), anyLong(), any());
        verify(proceedsService, never()).voidHeld(anyLong());
        assertThat(savedTransfers).isEmpty();
        assertThat(savedShipping).isEmpty();
        verifyNoInteractions(ledgerService, auditService);
    }

    private void assertRejected(Runnable call, AppErrorMessage error) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getError()).isEqualTo(error));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
