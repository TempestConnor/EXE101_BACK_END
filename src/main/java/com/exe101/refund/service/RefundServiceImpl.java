package com.exe101.refund.service;

import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.persistence.DatabaseErrors;
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
import com.exe101.order.service.OrderService;
import com.exe101.proceeds.model.Proceeds;
import com.exe101.proceeds.model.ProceedsStatus;
import com.exe101.proceeds.service.ProceedsService;
import com.exe101.refund.mapper.RefundEntityMapper;
import com.exe101.refund.model.CommissionRefundSettlement;
import com.exe101.refund.model.Refund;
import com.exe101.refund.model.RefundDecision;
import com.exe101.refund.model.RefundStatus;
import com.exe101.refund.model.RefundVerdict;
import com.exe101.refund.model.Transfer;
import com.exe101.refund.model.TransferOutcome;
import com.exe101.refund.model.TransferStatus;
import com.exe101.refund.repository.RefundRequestRepository;
import com.exe101.refund.repository.RefundTransferRepository;
import com.exe101.refund.repository.ShippingRefundRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
public class RefundServiceImpl implements RefundService {
    // The statuses UX_Refund_OpenItem allows once per item: an open or completed request.
    private static Set<String> openStatuses = Set.of(RefundStatus.REQUESTED.name(), RefundStatus.APPROVED.name(),
        RefundStatus.PROCESSING.name(), RefundStatus.REFUNDED.name());
    // An approved item counts as refunded for the shipping rule whatever its transfer's state (approved 1 November
    // 2026: shipping is decided at the approval that completes the physical portion).
    private static Set<String> approvedStatuses = Set.of(RefundStatus.APPROVED.name(),
        RefundStatus.PROCESSING.name(), RefundStatus.REFUNDED.name());
    // A failed transfer is retried on the same row; SUCCEEDED is final (approved 1 November 2026).
    private static Map<TransferStatus, Set<TransferStatus>> allowedOutcomes = Map.of(
        TransferStatus.PENDING, Set.of(TransferStatus.SUCCEEDED, TransferStatus.FAILED),
        TransferStatus.FAILED, Set.of(TransferStatus.PENDING));
    private static Set<FulfillmentStatus> closedFulfillments = Set.of(FulfillmentStatus.CANCELLED,
        FulfillmentStatus.FAILED);
    private static String transferProvider = "MANUAL";
    private static String approvedAction = "REFUND_APPROVED";
    private static String rejectedAction = "REFUND_REJECTED";
    private static String transferAction = "REFUND_TRANSFER_STATUS_CHANGED";
    private static String requestTarget = "REFUND_REQUEST";
    private static String currency = "VND";
    private static String transferTarget = "REFUND_TRANSFER";
    private final RefundRequestRepository repository;
    private final RefundTransferRepository transferRepository;
    private final ShippingRefundRepository shippingRepository;
    private final RefundEntityMapper mapper;
    private final OrderService orderService;
    private final FulfillmentService fulfillmentService;
    private final ProceedsService proceedsService;
    private final EntitlementService entitlementService;
    private final LedgerService ledgerService;
    private final AuditService auditService;

    // §16.1, amendment §C.9: the buyer asks for a whole physical item back, before delivery or within the return
    // window after it. The Order is locked first, as the window close locks it, so a request and the grant at the
    // window's close wait for each other and the loser sees the winner's result.
    @Override
    public Refund request(Long userId, Long orderId, Long orderItemId, String reason) {
        Order order = orderService.findLockedByItem(orderItemId)
            .filter(found -> found.getOrderId().equals(orderId) && found.getCustomerId().equals(userId))
            .orElseThrow(() -> new AppException(AppErrorMessage.ORDER_ITEM_NOT_FOUND));
        OrderLine line = line(order, orderItemId);
        OffsetDateTime requestedAt = now();

        if (!refundable(order, line, requestedAt)) {
            throw new AppException(AppErrorMessage.REFUND_NOT_ALLOWED);
        }

        if (repository.existsByOrderItemIdAndStatusIn(orderItemId, openStatuses)) {
            throw new AppException(AppErrorMessage.REFUND_REQUEST_OPEN);
        }

        Refund refund = Refund.builder()
            .withOrderItemId(orderItemId)
            .withRequestedByUserId(userId)
            .withReason(reason.strip())
            .withStatus(RefundStatus.REQUESTED)
            .withRequestedAt(requestedAt)
            .build();
        RefundRequest saved = saveRequest(mapper.toEntity(refund));
        log.info("[REFUND] - ACTION: request: userId: {}: orderItemId: {}: refundRequestId: {}", userId, orderItemId,
            saved.getRefundRequestId());

        return mapper.toModel(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Refund> listOwn(Long userId) {
        return refunds(repository.findByRequestedByUserIdOrderByRequestedAtDescRefundRequestIdDesc(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Refund> list(RefundStatus status) {
        List<RefundRequest> entities = status == null
            ? repository.findByOrderItemIdIsNotNullOrderByRequestedAtDescRefundRequestIdDesc()
            : repository.findByOrderItemIdIsNotNullAndStatusOrderByRequestedAtDescRefundRequestIdDesc(status.name());

        return refunds(entities);
    }

    // The reason check comes first, so a bad decision locks nothing. Then the Order is locked before the request,
    // so two decisions for one request, or for two items of one Order, wait for each other.
    @Override
    public Refund decide(Long staffId, Long refundRequestId, RefundDecision decision) {
        String reason = blankToNull(decision.getReason());

        if (decision.getVerdict() == RefundVerdict.REJECT && reason == null) {
            throw new AppException(AppErrorMessage.REFUND_DECISION_REASON_REQUIRED);
        }

        Long orderItemId = repository.findOrderItemIdByRefundRequestId(refundRequestId)
            .orElseThrow(() -> new AppException(AppErrorMessage.REFUND_REQUEST_NOT_FOUND));
        Order order = orderService.findLockedByItem(orderItemId)
            .orElseThrow(() -> new IllegalStateException("Refund request without an Order: " + refundRequestId));
        RefundRequest entity = repository.findWithLockByRefundRequestId(refundRequestId).orElseThrow();

        if (!RefundStatus.REQUESTED.name().equals(entity.getStatus())) {
            throw new AppException(AppErrorMessage.REFUND_ALREADY_DECIDED);
        }

        if (decision.getVerdict() == RefundVerdict.APPROVE) {
            return approve(staffId, order, entity, reason);
        }

        return reject(staffId, entity, reason);
    }

    // The reference check comes first, so a bad request locks nothing. Money may leave (SUCCEEDED) only while the
    // ledger and the records agree; FAILED and a retry keep the money owed and are always allowed. The transfer is
    // locked before its request, so two outcomes for one transfer wait for each other and the second is rejected.
    @Override
    public Refund recordTransferOutcome(Long staffId, Long transferId, TransferOutcome outcome) {
        TransferStatus target = outcome.getStatus();
        String reference = blankToNull(outcome.getTransferReference());

        if ((target == TransferStatus.SUCCEEDED) != (reference != null)) {
            throw new AppException(AppErrorMessage.REFUND_REFERENCE_INVALID);
        }

        if (target == TransferStatus.SUCCEEDED) {
            ledgerService.requireReconciled();
        }

        RefundTransfer transfer = transferRepository.findWithLockByRefundTransferId(transferId)
            .orElseThrow(() -> new AppException(AppErrorMessage.REFUND_TRANSFER_NOT_FOUND));
        TransferStatus current = TransferStatus.valueOf(transfer.getStatus());

        if (!allowedOutcomes.getOrDefault(current, Set.of()).contains(target)) {
            throw new AppException(AppErrorMessage.REFUND_TRANSFER_STATUS_CHANGE_NOT_ALLOWED);
        }

        RefundRequest request = repository.findWithLockByRefundRequestId(transfer.getRefundRequestId()).orElseThrow();

        if (!RefundStatus.APPROVED.name().equals(request.getStatus())) {
            throw new IllegalStateException("Open transfer for a request that is not approved: " + transferId);
        }

        OffsetDateTime changedAt = now();
        transfer.setStatus(target.name());

        if (target == TransferStatus.SUCCEEDED) {
            transfer.setProviderName(transferProvider);
            transfer.setProviderRefundRef(reference);
            transfer.setSucceededAt(changedAt);
            request.setStatus(RefundStatus.REFUNDED.name());
            repository.saveAndFlush(request);
        }

        RefundTransfer saved = saveTransfer(transfer);

        if (target == TransferStatus.SUCCEEDED) {
            // A Commission refund names the Quote its installment paid, as its approval did.
            Long quoteId = request.getCommissionId() == null ? null
                : ledgerService.findRefundQuoteId(request.getRefundRequestId()).orElseThrow();
            ledgerService.recordRefundPaid(RefundMovement.builder()
                .withSourceId(transferId)
                .withOrderItemId(request.getOrderItemId())
                .withQuoteId(quoteId)
                .withAmount(saved.getAmount())
                .withOccurredAt(changedAt)
                .build());
        }

        audit(staffId, transferAction, transferTarget, transferId, blankToNull(outcome.getReason()),
            "from=" + current + ";to=" + target + ";refundRequestId=" + request.getRefundRequestId()
                + (reference == null ? "" : ";transferReference=" + reference));
        log.info("[REFUND] - ACTION: recordTransferOutcome: staffId: {}: transferId: {}: status: {}", staffId,
            transferId, target);

        return refunds(List.of(request)).getFirst();
    }

    // F20: a paid Commission's refund is the percentage the Artist offers with a cancellation, for its Customer
    // (approved 2 November 2026). It stays REQUESTED until the cancellation is accepted, rejected or withdrawn. The
    // caller holds the Commission lock; UX_Refund_OpenCommission allows one open or completed refund per Commission.
    @Override
    public Refund openForCommission(
        Long commissionId, Long customerId, String reason, Integer percent, OffsetDateTime openedAt
    ) {
        Refund refund = Refund.builder()
            .withCommissionId(commissionId)
            .withRequestedByUserId(customerId)
            .withReason(reason)
            .withStatus(RefundStatus.REQUESTED)
            .withRequestedAt(openedAt)
            .withRefundPercent(percent)
            .build();
        RefundRequest saved = saveRequest(mapper.toEntity(refund));
        log.info("[REFUND] - ACTION: openForCommission: commissionId: {}: refundRequestId: {}", commissionId,
            saved.getRefundRequestId());

        return mapper.toModel(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Refund> listForCommission(Long commissionId) {
        return refunds(repository.findByCommissionIdOrderByRefundRequestIdAsc(commissionId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Refund> listCommissionRefunds(RefundStatus status) {
        List<RefundRequest> entities = status == null
            ? repository.findByCommissionIdIsNotNullOrderByRequestedAtDescRefundRequestIdDesc()
            : repository.findByCommissionIdIsNotNullAndStatusOrderByRequestedAtDescRefundRequestIdDesc(status.name());

        return refunds(entities);
    }

    // Taken after the Commission lock: the offer of the Commission's pending cancellation, if any.
    @Override
    public Optional<Refund> findOpenForCommission(Long commissionId) {
        return repository.findWithLockByCommissionIdAndStatus(commissionId, RefundStatus.REQUESTED.name())
            .map(mapper::toModel);
    }

    // A rejected or withdrawn cancellation takes its offer with it; the row stays as the history of the offer.
    @Override
    public Refund closeCommissionOffer(Long refundRequestId, String reason, OffsetDateTime closedAt) {
        RefundRequest entity = repository.findWithLockByRefundRequestId(refundRequestId).orElseThrow();

        if (!RefundStatus.REQUESTED.name().equals(entity.getStatus())) {
            throw new IllegalStateException("Refund offer already decided: " + refundRequestId);
        }

        entity.setStatus(RefundStatus.REJECTED.name());
        entity.setDecidedAt(closedAt);
        entity.setDecisionReason(reason);
        log.info("[REFUND] - ACTION: closeCommissionOffer: refundRequestId: {}", refundRequestId);

        return mapper.toModel(repository.saveAndFlush(entity));
    }

    // The Commission feature has checked, under its lock, that the share is still held. A refund of 0 closes the
    // request as REJECTED with no transfer, because a transfer must be above 0. Otherwise the request is APPROVED, the
    // Customer is owed the sum in REFUND_PAYABLE, and the Admin sends it by hand as for Order Items. The refunds of
    // one payment can never add up to more than it.
    @Override
    public Refund settleCommission(CommissionRefundSettlement settlement) {
        Long refundRequestId = settlement.getRefundRequestId();
        RefundRequest entity = repository.findWithLockByRefundRequestId(refundRequestId).orElseThrow();

        if (!RefundStatus.REQUESTED.name().equals(entity.getStatus())) {
            throw new AppException(AppErrorMessage.REFUND_ALREADY_DECIDED);
        }

        OffsetDateTime decidedAt = settlement.getDecidedAt();
        BigDecimal amount = settlement.getShare().add(settlement.getTax());
        entity.setRefundPercent(settlement.getPercent());
        entity.setDecidedAt(decidedAt);
        entity.setDecidedByStaffId(settlement.getDecidedByStaffId());
        entity.setDecisionReason(settlement.getDecisionReason());

        if (amount.signum() == 0) {
            entity.setStatus(RefundStatus.REJECTED.name());
            repository.saveAndFlush(entity);
            log.info("[REFUND] - ACTION: settleCommission: refundRequestId: {}: nothing refunded", refundRequestId);

            return refunds(List.of(entity)).getFirst();
        }

        entity.setStatus(RefundStatus.APPROVED.name());
        repository.saveAndFlush(entity);
        Long paymentId = settlement.getPaymentId();
        BigDecimal refunded = transferRepository.sumAmountByPaymentId(paymentId);

        if (refunded.add(amount).compareTo(settlement.getPaymentAmount()) > 0) {
            throw new IllegalStateException("Refunds would exceed the payment: " + paymentId);
        }

        Transfer transfer = Transfer.builder()
            .withRefundRequestId(refundRequestId)
            .withPaymentId(paymentId)
            .withAmount(amount)
            .withCurrencyCode(currency)
            .withStatus(TransferStatus.PENDING)
            .withCreatedAt(decidedAt)
            .build();
        transferRepository.saveAndFlush(mapper.toEntity(transfer));
        ledgerService.recordRefundApproved(RefundMovement.builder()
            .withSourceId(refundRequestId)
            .withArtistId(settlement.getArtistId())
            .withQuoteId(settlement.getQuoteId())
            .withProceeds(settlement.getShare())
            .withManufacturing(BigDecimal.ZERO)
            .withTax(settlement.getTax())
            .withShipping(BigDecimal.ZERO)
            .withAmount(amount)
            .withOccurredAt(decidedAt)
            .build());
        log.info("[REFUND] - ACTION: settleCommission: refundRequestId: {}: amount: {}", refundRequestId, amount);

        return refunds(List.of(entity)).getFirst();
    }

    // Everything is checked again under the Order, item and proceeds locks, in the order the window close takes
    // them, before anything is written: the refund must still reach only held money (amendment §B.8).
    private Refund approve(Long staffId, Order order, RefundRequest entity, String reason) {
        Long orderItemId = entity.getOrderItemId();
        OrderLine line = line(order, orderItemId);
        Optional<Fulfillment> fulfillment = fulfillmentService.findLocked(orderItemId);
        Optional<Proceeds> proceeds = proceedsService.findLockedByOrderItem(orderItemId);
        boolean paid = order.getStatus() == OrderStatus.PAID && order.getPayment() != null
            && order.getPayment().getStatus() == PaymentStatus.SUCCEEDED;
        boolean open = fulfillment.filter(found -> stillRefundable(found.getStatus(), found.getDeliveredAt(),
            entity.getRequestedAt())).isPresent();
        boolean held = proceeds.filter(found -> found.getStatus() == ProceedsStatus.HELD).isPresent();

        if (!paid || !open || !held || entitlementService.isGranted(orderItemId)) {
            throw new AppException(AppErrorMessage.REFUND_NOT_ALLOWED);
        }

        Long refundRequestId = entity.getRefundRequestId();
        OffsetDateTime decidedAt = now();
        entity.setStatus(RefundStatus.APPROVED.name());
        entity.setDecidedAt(decidedAt);
        entity.setDecidedByStaffId(staffId);
        entity.setDecisionReason(reason);
        repository.saveAndFlush(entity);

        if (fulfillment.get().getStatus() != FulfillmentStatus.DELIVERED) {
            fulfillmentService.cancel(staffId, orderItemId, "Refund request " + refundRequestId + " approved");
        }

        Proceeds voided = proceedsService.voidHeld(orderItemId);
        BigDecimal shipping = refundShipping(order, refundRequestId, decidedAt);
        // Tax included (approved 1 November 2026): the buyer gets back everything paid for the item.
        BigDecimal manufacturing = line.getManufacturingCostPerUnit().multiply(BigDecimal.valueOf(line.getQuantity()));
        BigDecimal amount = line.getLineSubtotal().add(line.getTaxAmount()).add(shipping);
        Long paymentId = order.getPayment().getPaymentId();
        BigDecimal refunded = transferRepository.sumAmountByPaymentId(paymentId);

        if (refunded.add(amount).compareTo(order.getPayment().getAmount()) > 0) {
            throw new IllegalStateException("Refunds would exceed the payment: " + paymentId);
        }

        Transfer transfer = Transfer.builder()
            .withRefundRequestId(refundRequestId)
            .withPaymentId(paymentId)
            .withAmount(amount)
            .withCurrencyCode(order.getCurrencyCode())
            .withStatus(TransferStatus.PENDING)
            .withCreatedAt(decidedAt)
            .build();
        RefundTransfer savedTransfer = transferRepository.saveAndFlush(mapper.toEntity(transfer));
        // The lines must add up to the amount, so a snapshot whose parts disagree with its total rolls back here.
        ledgerService.recordRefundApproved(RefundMovement.builder()
            .withSourceId(refundRequestId)
            .withArtistId(voided.getArtistId())
            .withOrderItemId(orderItemId)
            .withProceeds(voided.getAmount())
            .withManufacturing(manufacturing)
            .withTax(line.getTaxAmount())
            .withShipping(shipping)
            .withAmount(amount)
            .withOccurredAt(decidedAt)
            .build());
        audit(staffId, approvedAction, requestTarget, refundRequestId, reason, "orderItemId=" + orderItemId
            + ";amount=" + amount.toPlainString() + ";shipping=" + shipping.toPlainString() + ";transferId="
            + savedTransfer.getRefundTransferId());
        log.info("[REFUND] - ACTION: approve: staffId: {}: refundRequestId: {}: amount: {}", staffId,
            refundRequestId, amount);

        return refunds(List.of(entity)).getFirst();
    }

    // A rejected request lets the window close release the item as usual (amendment §B.7).
    private Refund reject(Long staffId, RefundRequest entity, String reason) {
        entity.setStatus(RefundStatus.REJECTED.name());
        entity.setDecidedAt(now());
        entity.setDecidedByStaffId(staffId);
        entity.setDecisionReason(reason);
        RefundRequest saved = repository.saveAndFlush(entity);
        audit(staffId, rejectedAction, requestTarget, saved.getRefundRequestId(), reason,
            "orderItemId=" + saved.getOrderItemId());
        log.info("[REFUND] - ACTION: reject: staffId: {}: refundRequestId: {}", staffId, saved.getRefundRequestId());

        return mapper.toModel(saved);
    }

    // §16.9, §16.13: the Order's shipping is refunded once, with the approval that leaves no physical item
    // unrefunded. This request is already APPROVED, so it counts; the Order lock keeps a sibling approval out, and
    // the ShippingRefund key is the database guard.
    private BigDecimal refundShipping(Order order, Long refundRequestId, OffsetDateTime decidedAt) {
        BigDecimal shipping = order.getShippingAmount();

        if (shipping.signum() == 0 || shippingRepository.existsById(order.getOrderId())) {
            return BigDecimal.ZERO;
        }

        List<Long> physicalItemIds = order.getItems().stream()
            .filter(item -> item.getListingType() == ListingType.PHYSICAL)
            .map(OrderLine::getOrderItemId)
            .toList();
        Set<Long> refundedItemIds = repository.findByOrderItemIdInAndStatusIn(physicalItemIds, approvedStatuses)
            .stream()
            .map(RefundRequest::getOrderItemId)
            .collect(Collectors.toSet());

        if (!refundedItemIds.containsAll(physicalItemIds)) {
            return BigDecimal.ZERO;
        }

        shippingRepository.saveAndFlush(mapper.toEntity(order.getOrderId(), refundRequestId, shipping, decidedAt));

        return shipping;
    }

    // Only a paid physical item that has started and not been cancelled, whose files the buyer does not hold yet.
    private boolean refundable(Order order, OrderLine line, OffsetDateTime requestedAt) {
        ItemFulfillment fulfillment = line.getFulfillment();

        if (order.getStatus() != OrderStatus.PAID || line.getListingType() != ListingType.PHYSICAL
            || fulfillment == null) {
            return false;
        }

        FulfillmentStatus status = FulfillmentStatus.valueOf(fulfillment.getStatus());

        return stillRefundable(status, fulfillment.getDeliveredAt(), requestedAt)
            && !entitlementService.isGranted(line.getOrderItemId());
    }

    // Before delivery any time (§16.11); after it, only a request made within the return window (amendment §C.9).
    private boolean stillRefundable(FulfillmentStatus status, OffsetDateTime deliveredAt, OffsetDateTime requestedAt) {
        if (closedFulfillments.contains(status)) {
            return false;
        }

        if (status != FulfillmentStatus.DELIVERED) {
            return true;
        }

        return requestedAt.isBefore(fulfillmentService.returnWindowClosesAt(deliveredAt));
    }

    private OrderLine line(Order order, Long orderItemId) {
        return order.getItems().stream()
            .filter(item -> item.getOrderItemId().equals(orderItemId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Order without its item: " + orderItemId));
    }

    private List<Refund> refunds(List<RefundRequest> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }

        List<Long> refundRequestIds = entities.stream().map(RefundRequest::getRefundRequestId).toList();
        Map<Long, Transfer> transfers = transferRepository.findByRefundRequestIdIn(refundRequestIds).stream()
            .map(mapper::toModel)
            .collect(Collectors.toMap(Transfer::getRefundRequestId, Function.identity()));
        Map<Long, BigDecimal> shipping = shippingRepository.findByRefundRequestIdIn(refundRequestIds).stream()
            .collect(Collectors.toMap(ShippingRefund::getRefundRequestId, ShippingRefund::getAmount));

        return entities.stream()
            .map(entity -> {
                Refund refund = mapper.toModel(entity);
                refund.setTransfer(transfers.get(entity.getRefundRequestId()));
                refund.setShippingAmount(shipping.get(entity.getRefundRequestId()));

                return refund;
            })
            .toList();
    }

    private RefundRequest saveRequest(RefundRequest entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseErrors.isDuplicateKey(exception)) {
                throw new AppException(AppErrorMessage.REFUND_REQUEST_OPEN);
            }

            throw exception;
        }
    }

    private RefundTransfer saveTransfer(RefundTransfer transfer) {
        try {
            return transferRepository.saveAndFlush(transfer);
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseErrors.isDuplicateKey(exception)) {
                throw new AppException(AppErrorMessage.REFUND_REFERENCE_IN_USE);
            }

            throw exception;
        }
    }

    private void audit(
        Long staffId, String actionType, String targetType, Long targetId, String reason, String details
    ) {
        AuditEntry entry = AuditEntry.builder()
            .withStaffId(staffId)
            .withActionType(actionType)
            .withTargetType(targetType)
            .withTargetId(targetId)
            .withReason(reason)
            .withDetails(details)
            .build();
        auditService.record(entry);
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
