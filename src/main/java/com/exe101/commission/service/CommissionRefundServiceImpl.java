package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.commission.model.CommissionRefund;
import com.exe101.commission.model.CommissionRefundDecision;
import com.exe101.commission.model.CommissionRefundStage;
import com.exe101.commission.model.QuoteSplit;
import com.exe101.commission.model.QuoteStatus;
import com.exe101.commission.repository.CommissionEventRepository;
import com.exe101.commission.repository.CommissionQuoteRepository;
import com.exe101.commission.repository.CommissionRepository;
import com.exe101.commission.repository.QuotePaymentRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.Commission;
import com.exe101.entity.CommissionEvent;
import com.exe101.entity.CommissionQuote;
import com.exe101.entity.Payment;
import com.exe101.order.model.PaymentStatus;
import com.exe101.proceeds.model.Proceeds;
import com.exe101.proceeds.model.ProceedsStatus;
import com.exe101.proceeds.service.ProceedsService;
import com.exe101.refund.model.CommissionRefundSettlement;
import com.exe101.refund.model.Refund;
import com.exe101.refund.model.RefundStatus;
import com.exe101.refund.service.RefundService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// F20 (approved 2 November 2026): cancelling a paid Commission opens its refund without anyone asking. The Artist
// enters the percentage of the held first payment that their own terms give for the work done; the Customer accepts
// it, or objects and the Admin sets the final one, audited. The Admin may also set it when the Artist or the Customer
// does not act. The refund returns that percentage of the Artist's held share and of the tax half, each rounded
// half-up, and the rest of the share is released to the Artist at once. A dispute resolved CANCEL_NO_REFUND releases
// the whole held share. Every action locks the Commission row first, then the refund request, then the proceeds, so a
// Customer's acceptance and an Admin decision have exactly one winner.
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CommissionRefundServiceImpl implements CommissionRefundService {
    private static int maxTextLength = 5000;
    private static int maxStaffReasonLength = 2000;
    private static int maxPercent = 100;
    private static int moneyScale = 4;
    private static Integer firstInstallment = 1;
    private static String openedEvent = "REFUND_OPENED";
    private static String percentEvent = "REFUND_PERCENT_SET";
    private static String acceptedEvent = "REFUND_ACCEPTED";
    private static String objectedEvent = "REFUND_OBJECTED";
    private static String decidedEvent = "REFUND_DECIDED";
    private static String decidedAction = "COMMISSION_REFUND_DECIDED";
    private static String requestTarget = "REFUND_REQUEST";
    private final CommissionRepository repository;
    private final CommissionQuoteRepository quoteRepository;
    private final QuotePaymentRepository paymentRepository;
    private final CommissionEventRepository eventRepository;
    private final ArtistService artistService;
    private final RefundService refundService;
    private final ProceedsService proceedsService;
    private final AuditService auditService;

    // Called by the cancellation, with the Commission locked. Only a Commission with a held first payment has
    // something to refund; one cancelled before it has none (amendment §D.12).
    @Override
    public void openOnCancellation(
        Long commissionId, Long actorUserId, Long actorStaffId, String reason, OffsetDateTime cancelledAt
    ) {
        if (held(commissionId).isEmpty()) {
            return;
        }

        Commission commission = repository.findById(commissionId).orElseThrow();
        Refund opened = refundService.openForCommission(commissionId, commission.getCustomerId(), reason,
            cancelledAt);
        record(commissionId, openedEvent, opened.getRefundRequestId(), actorUserId, actorStaffId, null, cancelledAt);
        log.info("[COMMISSION] - ACTION: openRefund: commissionId: {}: refundRequestId: {}", commissionId,
            opened.getRefundRequestId());
    }

    // CANCEL_NO_REFUND: the held installments go to the Artist, as at completion.
    @Override
    public void releaseOnCancellation(Long commissionId, OffsetDateTime cancelledAt) {
        for (CommissionQuote quote : quoteRepository.findByCommissionIdAndStatus(commissionId,
            QuoteStatus.ACCEPTED.name())) {
            List<Proceeds> released = proceedsService.releaseQuote(quote.getQuoteId(), cancelledAt);
            log.info("[COMMISSION] - ACTION: releaseOnCancellation: commissionId: {}: quoteId: {}: released: {}",
                commissionId, quote.getQuoteId(), released.size());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommissionRefund> list(Long userId, Long commissionId) {
        Commission commission = repository.findById(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_NOT_FOUND));
        isArtist(userId, commission);

        return refundService.listForCommission(commissionId).stream()
            .map(this::toModel)
            .toList();
    }

    // The Artist enters the percentage once; the Customer then answers it.
    @Override
    public CommissionRefund setPercent(Long userId, Long commissionId, Long refundRequestId, Integer percent) {
        requirePercent(percent);
        Commission commission = lock(commissionId);

        if (!isArtist(userId, commission)) {
            throw new AppException(AppErrorMessage.ACCESS_DENIED);
        }

        Refund refund = refundService.findLockedForCommission(commissionId, refundRequestId);

        if (requireOpen(refund) != CommissionRefundStage.AWAITING_PERCENTAGE) {
            throw new AppException(AppErrorMessage.COMMISSION_REFUND_ACTION_NOT_ALLOWED);
        }

        Refund updated = refundService.recordPercent(refundRequestId, percent);
        record(commissionId, percentEvent, refundRequestId, userId, null, "percent=" + percent, now());
        log.info("[COMMISSION] - ACTION: setRefundPercent: commissionId: {}: refundRequestId: {}: percent: {}",
            commissionId, refundRequestId, percent);

        return toModel(updated);
    }

    // The Customer's acceptance is the agreement: the refund is settled at once.
    @Override
    public CommissionRefund accept(Long userId, Long commissionId, Long refundRequestId) {
        Commission commission = lock(commissionId);

        if (isArtist(userId, commission)) {
            throw new AppException(AppErrorMessage.ACCESS_DENIED);
        }

        Refund refund = refundService.findLockedForCommission(commissionId, refundRequestId);

        if (requireOpen(refund) != CommissionRefundStage.AWAITING_CUSTOMER) {
            throw new AppException(AppErrorMessage.COMMISSION_REFUND_ACTION_NOT_ALLOWED);
        }

        OffsetDateTime acceptedAt = now();
        Refund settled = settle(commission, refund, refund.getRefundPercent(), null, null, acceptedAt);
        record(commissionId, acceptedEvent, refundRequestId, userId, null, "percent=" + refund.getRefundPercent(),
            acceptedAt);
        log.info("[COMMISSION] - ACTION: acceptRefund: commissionId: {}: refundRequestId: {}: status: {}",
            commissionId, refundRequestId, settled.getStatus());

        return toModel(settled);
    }

    // An objection hands the decision to the Admin; the reason is kept on the event.
    @Override
    public CommissionRefund object(Long userId, Long commissionId, Long refundRequestId, String reason) {
        String text = requireText(reason);
        Commission commission = lock(commissionId);

        if (isArtist(userId, commission)) {
            throw new AppException(AppErrorMessage.ACCESS_DENIED);
        }

        Refund refund = refundService.findLockedForCommission(commissionId, refundRequestId);

        if (requireOpen(refund) != CommissionRefundStage.AWAITING_CUSTOMER) {
            throw new AppException(AppErrorMessage.COMMISSION_REFUND_ACTION_NOT_ALLOWED);
        }

        record(commissionId, objectedEvent, refundRequestId, userId, null, text, now());
        log.info("[COMMISSION] - ACTION: objectRefund: commissionId: {}: refundRequestId: {}", commissionId,
            refundRequestId);

        return toModel(refund);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommissionRefund> listForStaff(RefundStatus status) {
        return refundService.listCommissionRefunds(status).stream()
            .map(this::toModel)
            .toList();
    }

    // The Admin's percentage is final, at any open step, and is audited with the action (§29).
    @Override
    public CommissionRefund decide(Long staffId, Long refundRequestId, CommissionRefundDecision decision) {
        Integer percent = decision == null ? null : decision.getPercent();
        String reason = decision == null || decision.getReason() == null ? "" : decision.getReason().strip();
        requirePercent(percent);

        if (reason.isEmpty() || reason.length() > maxStaffReasonLength) {
            throw new AppException(AppErrorMessage.COMMISSION_REFUND_REASON_INVALID);
        }

        Long commissionId = refundService.findCommissionId(refundRequestId)
            .orElseThrow(() -> new AppException(AppErrorMessage.REFUND_REQUEST_NOT_FOUND));
        Commission commission = repository.findWithLockByCommissionId(commissionId).orElseThrow();
        Refund refund = refundService.findLockedForCommission(commissionId, refundRequestId);
        CommissionRefundStage stage = requireOpen(refund);
        OffsetDateTime decidedAt = now();
        Refund settled = settle(commission, refund, percent, staffId, reason, decidedAt);
        record(commissionId, decidedEvent, refundRequestId, null, staffId, "percent=" + percent, decidedAt);
        BigDecimal amount = settled.getTransfer() == null ? BigDecimal.ZERO : settled.getTransfer().getAmount();
        auditService.record(AuditEntry.builder()
            .withStaffId(staffId)
            .withActionType(decidedAction)
            .withTargetType(requestTarget)
            .withTargetId(refundRequestId)
            .withReason(reason)
            .withDetails("commissionId=" + commissionId + ";percent=" + percent + ";from=" + stage + ";status="
                + settled.getStatus() + ";amount=" + amount.toPlainString()
                + (settled.getTransfer() == null ? "" : ";transferId=" + settled.getTransfer().getTransferId()))
            .build());
        log.info("[COMMISSION] - ACTION: decideRefund: staffId: {}: refundRequestId: {}: percent: {}", staffId,
            refundRequestId, percent);

        return toModel(settled);
    }

    // Checked under the locks before anything is written: the first installment's share must still be held
    // (amendment §B.8). The percentage of the share and of the tax half are each rounded half-up to whole VND.
    private Refund settle(
        Commission commission, Refund refund, Integer percent, Long staffId, String reason, OffsetDateTime settledAt
    ) {
        Held held = held(commission.getCommissionId())
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_REFUND_NOT_ALLOWED));
        CommissionQuote quote = held.quote();
        BigDecimal taxHalf = QuoteSplit.of(quote.getPrice(), quote.getTaxAmount()).getFirstTax();
        BigDecimal share = percentOf(held.proceeds().getAmount(), percent);
        Refund settled = refundService.settleCommission(CommissionRefundSettlement.builder()
            .withRefundRequestId(refund.getRefundRequestId())
            .withPercent(percent)
            .withArtistId(held.proceeds().getArtistId())
            .withQuoteId(quote.getQuoteId())
            .withPaymentId(held.payment().getPaymentId())
            .withPaymentAmount(held.payment().getAmount())
            .withShare(share)
            .withTax(percentOf(taxHalf, percent))
            .withDecidedAt(settledAt)
            .withDecidedByStaffId(staffId)
            .withDecisionReason(reason)
            .build());
        proceedsService.refundQuoteShare(held.proceeds().getProceedsId(), share, settledAt);

        return settled;
    }

    // The accepted Quote whose first installment was paid and whose share is still held.
    private Optional<Held> held(Long commissionId) {
        for (CommissionQuote quote : quoteRepository.findByCommissionIdAndStatus(commissionId,
            QuoteStatus.ACCEPTED.name())) {
            Optional<Payment> payment = paymentRepository.findByQuoteIdAndInstallment(quote.getQuoteId(),
                    firstInstallment)
                .filter(found -> PaymentStatus.SUCCEEDED.name().equals(found.getStatus()));
            Optional<Proceeds> proceeds = proceedsService.findLockedByQuote(quote.getQuoteId()).stream()
                .filter(found -> firstInstallment.equals(found.getInstallment())
                    && found.getStatus() == ProceedsStatus.HELD)
                .findFirst();

            if (payment.isPresent() && proceeds.isPresent()) {
                return Optional.of(new Held(quote, payment.get(), proceeds.get()));
            }
        }

        return Optional.empty();
    }

    private CommissionRefundStage requireOpen(Refund refund) {
        if (refund.getStatus() != RefundStatus.REQUESTED) {
            throw new AppException(AppErrorMessage.REFUND_ALREADY_DECIDED);
        }

        return stage(refund);
    }

    private CommissionRefundStage stage(Refund refund) {
        if (refund.getStatus() != RefundStatus.REQUESTED) {
            return null;
        }

        if (refund.getRefundPercent() == null) {
            return CommissionRefundStage.AWAITING_PERCENTAGE;
        }

        boolean objected = eventRepository.findFirstByCommissionIdAndEventTypeAndRelatedIdOrderByEventIdDesc(
            refund.getCommissionId(), objectedEvent, refund.getRefundRequestId()).isPresent();

        return objected ? CommissionRefundStage.AWAITING_ADMIN : CommissionRefundStage.AWAITING_CUSTOMER;
    }

    private CommissionRefund toModel(Refund refund) {
        return CommissionRefund.builder()
            .withRefundRequestId(refund.getRefundRequestId())
            .withCommissionId(refund.getCommissionId())
            .withStatus(refund.getStatus())
            .withStage(stage(refund))
            .withRefundPercent(refund.getRefundPercent())
            .withReason(refund.getReason())
            .withRequestedAt(refund.getRequestedAt())
            .withDecidedAt(refund.getDecidedAt())
            .withDecisionReason(refund.getDecisionReason())
            .withTransfer(refund.getTransfer())
            .build();
    }

    private BigDecimal percentOf(BigDecimal amount, Integer percent) {
        return amount.multiply(BigDecimal.valueOf(percent))
            .divide(BigDecimal.valueOf(maxPercent), 0, RoundingMode.HALF_UP)
            .setScale(moneyScale);
    }

    private void requirePercent(Integer percent) {
        if (percent == null || percent < 0 || percent > maxPercent) {
            throw new AppException(AppErrorMessage.REFUND_PERCENT_INVALID);
        }
    }

    private void record(
        Long commissionId, String eventType, Long relatedId, Long userId, Long staffId, String details,
        OffsetDateTime occurredAt
    ) {
        eventRepository.saveAndFlush(new CommissionEvent(commissionId, eventType, null, null, relatedId, userId,
            staffId, occurredAt, details));
    }

    private Commission lock(Long commissionId) {
        return repository.findWithLockByCommissionId(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_NOT_FOUND));
    }

    // Only the Customer and the commissioned Artist see a Commission; to anyone else it does not exist.
    private boolean isArtist(Long userId, Commission commission) {
        if (commission.getCustomerId() == userId) {
            return false;
        }

        Artist artist = artistService.get(commission.getArtistId());

        if (!artist.getUserId().equals(userId)) {
            throw new AppException(AppErrorMessage.COMMISSION_NOT_FOUND);
        }

        return true;
    }

    private String requireText(String value) {
        String text = value == null ? "" : value.strip();

        if (text.isEmpty() || text.length() > maxTextLength) {
            throw new AppException(AppErrorMessage.COMMISSION_INVALID);
        }

        return text;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private record Held(CommissionQuote quote, Payment payment, Proceeds proceeds) {
    }
}
