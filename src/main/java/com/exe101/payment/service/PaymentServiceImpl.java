package com.exe101.payment.service;

import com.exe101.entitlement.service.EntitlementService;
import com.exe101.entity.PaymentAttempt;
import com.exe101.order.model.Order;
import com.exe101.order.model.PaymentSettlement;
import com.exe101.order.model.PaymentTarget;
import com.exe101.order.service.OrderService;
import com.exe101.payment.mapper.PaymentEntityMapper;
import com.exe101.payment.model.Attempt;
import com.exe101.payment.model.AttemptStatus;
import com.exe101.payment.model.EventOutcome;
import com.exe101.payment.model.NotificationResult;
import com.exe101.payment.model.PaymentEvent;
import com.exe101.payment.model.PaymentStart;
import com.exe101.payment.model.ProviderNotification;
import com.exe101.payment.model.VnpayReply;
import com.exe101.payment.repository.PaymentAttemptRepository;
import com.exe101.payment.repository.ProviderEventRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {
    private static Set<String> openStatuses = Set.of(AttemptStatus.INITIATED.name(), AttemptStatus.PENDING.name());
    private final PaymentAttemptRepository attemptRepository;
    private final ProviderEventRepository eventRepository;
    private final PaymentEntityMapper mapper;
    private final OrderService orderService;
    private final VnpayGateway gateway;
    private final EntitlementService entitlementService;

    // Every call is a new attempt (§12.4); only a provider-confirmed success has any effect on the Order.
    @Override
    public PaymentStart start(Long userId, Long orderId, String clientIp) {
        Order order = orderService.getPayable(userId, orderId);
        PaymentTarget payment = order.getPayment();
        OffsetDateTime createdAt = now();
        String attemptRef = UUID.randomUUID().toString().replace("-", "");
        Attempt attempt = Attempt.builder()
            .withPaymentId(payment.getPaymentId())
            .withProviderName(VnpayGateway.providerName)
            .withProviderAttemptRef(attemptRef)
            .withStatus(AttemptStatus.INITIATED)
            .withCreatedAt(createdAt)
            .build();
        PaymentAttempt saved = attemptRepository.saveAndFlush(mapper.toEntity(attempt));
        String paymentUrl = gateway.paymentUrl(attemptRef, payment.getAmount(), orderId, createdAt,
            order.getExpiresAt(), clientIp);
        log.info("[PAYMENT] - ACTION: start: userId: {}: orderId: {}: attemptId: {}", userId, orderId,
            saved.getAttemptId());

        return PaymentStart.builder()
            .withAttemptId(saved.getAttemptId())
            .withOrderId(orderId)
            .withPaymentUrl(paymentUrl)
            .withExpiresAt(order.getExpiresAt())
            .build();
    }

    // One transaction per notification. Locks are taken in a fixed order (event key, attempt, then Order and
    // Payment inside the Order service), and the event row is written last, so a failure leaves nothing recorded
    // and the provider's retry is handled afresh.
    @Override
    public NotificationResult handleVnpayNotification(Map<String, String> parameters) {
        Optional<ProviderNotification> verified = gateway.verify(parameters);

        if (verified.isEmpty()) {
            log.warn("[PAYMENT] - ACTION: notification: outcome: rejected signature");
            return NotificationResult.builder().withReply(VnpayReply.INVALID_SIGNATURE).build();
        }

        ProviderNotification notification = verified.get();
        Optional<EventOutcome> earlier = eventRepository
            .findWithLockByProviderNameAndExternalEventId(VnpayGateway.providerName, notification.getEventId())
            .map(event -> EventOutcome.valueOf(event.getOutcome()));

        if (earlier.isPresent()) {
            return NotificationResult.builder()
                .withOutcome(earlier.get())
                .withDuplicate(true)
                .withReply(VnpayReply.ALREADY_CONFIRMED)
                .build();
        }

        OffsetDateTime receivedAt = now();
        Optional<PaymentAttempt> found = attemptRepository
            .findWithLockByProviderNameAndProviderAttemptRef(VnpayGateway.providerName, notification.getAttemptRef());

        // A success nobody started is money to reconcile; a failure nobody started changes nothing.
        if (found.isEmpty()) {
            EventOutcome outcome = notification.isSuccess() ? EventOutcome.EXCEPTION : EventOutcome.IGNORED;

            return record(notification, null, receivedAt, outcome, VnpayReply.ORDER_NOT_FOUND);
        }

        PaymentAttempt attempt = found.get();

        if (!openStatuses.contains(attempt.getStatus())) {
            return record(notification, attempt, receivedAt, EventOutcome.IGNORED, VnpayReply.ALREADY_CONFIRMED);
        }

        if (!notification.isSuccess()) {
            attempt.setStatus(AttemptStatus.FAILED.name());
            attemptRepository.saveAndFlush(attempt);

            return record(notification, attempt, receivedAt, EventOutcome.PROCESSED, VnpayReply.CONFIRMED);
        }

        PaymentSettlement settlement = orderService.settlePayment(attempt.getPaymentId(), notification.getAmount(),
            receivedAt);
        boolean paid = settlement == PaymentSettlement.PAID;
        attempt.setStatus(paid ? AttemptStatus.SUCCEEDED.name() : AttemptStatus.EXCEPTION.name());
        attempt.setProviderTransactionRef(notification.getTransactionRef());
        attempt.setConfirmedAt(receivedAt);
        attemptRepository.saveAndFlush(attempt);

        // Side effects of a confirmed payment share its transaction; an exception never grants anything (§12.12).
        if (paid) {
            Order order = orderService.getLockedByPayment(attempt.getPaymentId());
            entitlementService.grantDigital(order);
        }

        return switch (settlement) {
            case PAID -> record(notification, attempt, receivedAt, EventOutcome.PROCESSED, VnpayReply.CONFIRMED);
            case WRONG_AMOUNT -> record(notification, attempt, receivedAt, EventOutcome.EXCEPTION,
                VnpayReply.INVALID_AMOUNT);
            case ALREADY_PAID, NOT_PAYABLE -> record(notification, attempt, receivedAt, EventOutcome.EXCEPTION,
                VnpayReply.ALREADY_CONFIRMED);
        };
    }

    private NotificationResult record(
        ProviderNotification notification, PaymentAttempt attempt, OffsetDateTime receivedAt, EventOutcome outcome,
        VnpayReply reply
    ) {
        Long attemptId = attempt == null ? null : attempt.getAttemptId();
        PaymentEvent event = PaymentEvent.builder()
            .withProviderName(VnpayGateway.providerName)
            .withExternalEventId(notification.getEventId())
            .withAttemptId(attemptId)
            .withReceivedAt(receivedAt)
            .withProcessedAt(receivedAt)
            .withOutcome(outcome)
            .build();
        eventRepository.saveAndFlush(mapper.toEntity(event));
        log.info("[PAYMENT] - ACTION: notification: attemptId: {}: outcome: {}: reply: {}", attemptId, outcome,
            reply.getCode());

        return NotificationResult.builder().withOutcome(outcome).withReply(reply).build();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
