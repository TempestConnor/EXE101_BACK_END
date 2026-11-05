package com.exe101.payment.service;

import com.exe101.commission.model.QuotePayment;
import com.exe101.commission.model.QuoteSettlement;
import com.exe101.commission.service.CommissionPaymentService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entitlement.service.EntitlementService;
import com.exe101.entity.PaymentAttempt;
import com.exe101.entity.ProviderEvent;
import com.exe101.fulfillment.service.FulfillmentService;
import com.exe101.ledger.service.LedgerService;
import com.exe101.order.config.OrderProperties;
import com.exe101.order.model.Order;
import com.exe101.order.model.PaymentSettlement;
import com.exe101.order.model.PaymentTarget;
import com.exe101.order.service.OrderService;
import com.exe101.payment.mapper.PaymentEntityMapper;
import com.exe101.payment.model.Attempt;
import com.exe101.payment.model.AttemptLookup;
import com.exe101.payment.model.AttemptStatus;
import com.exe101.payment.model.EventOutcome;
import com.exe101.payment.model.NotificationResult;
import com.exe101.payment.model.PaymentEvent;
import com.exe101.payment.model.PaymentStart;
import com.exe101.payment.model.ProviderNotification;
import com.exe101.payment.model.VnpayReply;
import com.exe101.payment.repository.PaymentAttemptRepository;
import com.exe101.payment.repository.ProviderEventRepository;
import com.exe101.proceeds.service.ProceedsService;
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
    private final FulfillmentService fulfillmentService;
    private final ProceedsService proceedsService;
    private final LedgerService ledgerService;
    private final CommissionPaymentService commissionPaymentService;
    private final OrderProperties orderProperties;

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
        String paymentUrl = gateway.paymentUrl(attemptRef, payment.getAmount(), "Thanh toan don hang " + orderId,
            createdAt, order.getExpiresAt(), clientIp);
        log.info("[PAYMENT] - ACTION: start: userId: {}: orderId: {}: attemptId: {}", userId, orderId,
            saved.getAttemptId());

        return PaymentStart.builder()
            .withAttemptId(saved.getAttemptId())
            .withOrderId(orderId)
            .withPaymentUrl(paymentUrl)
            .withExpiresAt(order.getExpiresAt())
            .build();
    }

    // A Quote has no payment window; each VNPay page stays open as long as an Order's would (approved 2 November
    // 2026). The Payment is prepared under the Commission lock, and the attempt joins its transaction.
    @Override
    public PaymentStart startQuote(Long userId, Long commissionId, Long quoteId, String clientIp) {
        QuotePayment payment = commissionPaymentService.preparePayment(userId, commissionId, quoteId);
        OffsetDateTime createdAt = now();
        OffsetDateTime expiresAt = createdAt.plus(orderProperties.paymentWindow());
        String attemptRef = UUID.randomUUID().toString().replace("-", "");
        Attempt attempt = Attempt.builder()
            .withPaymentId(payment.getPaymentId())
            .withProviderName(VnpayGateway.providerName)
            .withProviderAttemptRef(attemptRef)
            .withStatus(AttemptStatus.INITIATED)
            .withCreatedAt(createdAt)
            .build();
        PaymentAttempt saved = attemptRepository.saveAndFlush(mapper.toEntity(attempt));
        String paymentUrl = gateway.paymentUrl(attemptRef, payment.getAmount(), "Thanh toan commission "
            + commissionId, createdAt, expiresAt, clientIp);
        log.info("[PAYMENT] - ACTION: startQuote: userId: {}: quoteId: {}: attemptId: {}", userId, quoteId,
            saved.getAttemptId());

        return PaymentStart.builder()
            .withAttemptId(saved.getAttemptId())
            .withCommissionId(commissionId)
            .withQuoteId(quoteId)
            .withInstallment(payment.getInstallment())
            .withAmount(payment.getAmount())
            .withPaymentUrl(paymentUrl)
            .withExpiresAt(expiresAt)
            .build();
    }

    // For the buyer's page after VNPay: what the attempt pays for and the status the signed IPN recorded. Read-only,
    // no locks, nothing written; the return URL's own parameters are never trusted. Another buyer's attempt answers
    // exactly as an unknown one does, so a reference cannot be confirmed to exist.
    @Override
    @Transactional(readOnly = true)
    public AttemptLookup findAttempt(Long userId, String attemptRef) {
        PaymentAttempt attempt = attemptRepository
            .findByProviderNameAndProviderAttemptRef(VnpayGateway.providerName, attemptRef)
            .orElseThrow(() -> new AppException(AppErrorMessage.PAYMENT_ATTEMPT_NOT_FOUND));
        AttemptLookup.AttemptLookupBuilder lookup = AttemptLookup.builder()
            .withAttemptRef(attempt.getProviderAttemptRef())
            .withStatus(AttemptStatus.valueOf(attempt.getStatus()));
        Optional<Long> orderId = orderService.findOwnOrderIdByPayment(userId, attempt.getPaymentId());

        if (orderId.isPresent()) {
            return lookup.withOrderId(orderId.get()).build();
        }

        QuotePayment quote = commissionPaymentService.findOwnPayment(userId, attempt.getPaymentId())
            .orElseThrow(() -> new AppException(AppErrorMessage.PAYMENT_ATTEMPT_NOT_FOUND));

        return lookup
            .withCommissionId(quote.getCommissionId())
            .withQuoteId(quote.getQuoteId())
            .withInstallment(quote.getInstallment())
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

        // The Payment pays an Order or a Quote installment, never both (CK_Payment); a Quote locks its Commission where
        // an Order locks the Order, each before the Payment.
        QuoteSettlement quote = null;
        PaymentSettlement settlement;

        if (commissionPaymentService.isQuotePayment(attempt.getPaymentId())) {
            quote = commissionPaymentService.settlePayment(attempt.getPaymentId(), notification.getAmount(),
                receivedAt);
            settlement = quote.getSettlement();
        } else {
            settlement = orderService.settlePayment(attempt.getPaymentId(), notification.getAmount(), receivedAt);
        }

        boolean paid = settlement == PaymentSettlement.PAID;
        attempt.setStatus(paid ? AttemptStatus.SUCCEEDED.name() : AttemptStatus.EXCEPTION.name());
        attempt.setProviderTransactionRef(notification.getTransactionRef());
        attempt.setConfirmedAt(receivedAt);
        attemptRepository.saveAndFlush(attempt);

        // Side effects of a confirmed payment share its transaction; an exception never grants or holds anything
        // (§12.12).
        if (paid && quote != null) {
            ledgerService.recordQuotePayment(quote);
            proceedsService.holdQuote(quote);

            // The final installment completed the Commission: the full Quote amount is released (amendment §D.13).
            if (quote.isCompleted()) {
                proceedsService.releaseQuote(quote.getQuoteId(), quote.getPaidAt());
            }
        } else if (paid) {
            Order order = orderService.getLockedByPayment(attempt.getPaymentId());
            entitlementService.grantDigital(order);
            fulfillmentService.start(order);
            ledgerService.recordPayment(order);
            proceedsService.recordPaid(order);
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
        ProviderEvent saved = eventRepository.saveAndFlush(mapper.toEntity(event));

        // Every exception is a confirmed success: real money that applies to no Order, kept apart in the ledger.
        if (outcome == EventOutcome.EXCEPTION) {
            ledgerService.recordUnapplied(saved.getProviderEventId(), notification.getAmount(), receivedAt);
        }

        log.info("[PAYMENT] - ACTION: notification: attemptId: {}: outcome: {}: reply: {}", attemptId, outcome,
            reply.getCode());

        return NotificationResult.builder().withOutcome(outcome).withReply(reply).build();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
