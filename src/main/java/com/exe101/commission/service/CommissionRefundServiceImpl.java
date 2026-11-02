package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.commission.model.CommissionRefund;
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
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// F20 (approved 2 November 2026): the refund of a paid Commission is part of its cancellation agreement. The Artist
// offers a percentage of the held first payment under their own terms, with their cancellation request or in answer
// to the Customer's; the offer is a REQUESTED refund. The Customer's acceptance cancels the Commission and settles
// the refund in the same transaction; a rejected or withdrawn cancellation closes the offer as REJECTED. A dispute
// resolved CANCEL_REFUND carries the Admin's percentage, and CANCEL_NO_REFUND releases the whole held share. The
// refund returns the percentage of the Artist's held share and of the tax half, each rounded half-up; the rest of
// the share is released to the Artist at once. Callers hold the Commission lock; the refund and then the proceeds
// are locked after it.
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CommissionRefundServiceImpl implements CommissionRefundService {
    private static int maxPercent = 100;
    private static int moneyScale = 4;
    private static Integer firstInstallment = 1;
    private static String offeredEvent = "REFUND_OFFERED";
    private final CommissionRepository repository;
    private final CommissionQuoteRepository quoteRepository;
    private final QuotePaymentRepository paymentRepository;
    private final CommissionEventRepository eventRepository;
    private final ArtistService artistService;
    private final RefundService refundService;
    private final ProceedsService proceedsService;

    // One offer at a time, and only while the first installment's share is still held.
    @Override
    public void offer(Long commissionId, Long artistUserId, Integer percent, String reason, OffsetDateTime offeredAt) {
        requireSettleable(commissionId);

        if (refundService.findOpenForCommission(commissionId).isPresent()) {
            throw new AppException(AppErrorMessage.COMMISSION_REFUND_ACTION_NOT_ALLOWED);
        }

        Commission commission = repository.findById(commissionId).orElseThrow();
        Refund offered = refundService.openForCommission(commissionId, commission.getCustomerId(), reason, percent,
            offeredAt);
        eventRepository.saveAndFlush(new CommissionEvent(commissionId, offeredEvent, null, null,
            offered.getRefundRequestId(), artistUserId, null, offeredAt, "percent=" + percent));
        log.info("[COMMISSION] - ACTION: offerRefund: commissionId: {}: refundRequestId: {}: percent: {}",
            commissionId, offered.getRefundRequestId(), percent);
    }

    @Override
    public Optional<Integer> findOfferPercent(Long commissionId) {
        return refundService.findOpenForCommission(commissionId).map(Refund::getRefundPercent);
    }

    // Amendment §B.8: a refund only ever reaches money still held.
    @Override
    public void requireSettleable(Long commissionId) {
        if (held(commissionId).isEmpty()) {
            throw new AppException(AppErrorMessage.COMMISSION_REFUND_NOT_ALLOWED);
        }
    }

    @Override
    public void closeOffer(Long commissionId, String reason, OffsetDateTime closedAt) {
        refundService.findOpenForCommission(commissionId)
            .ifPresent(offer -> refundService.closeCommissionOffer(offer.getRefundRequestId(), reason, closedAt));
    }

    // The Customer's acceptance: the offered percentage is settled as agreed.
    @Override
    public void settleOffer(Long commissionId, OffsetDateTime acceptedAt) {
        Refund offer = refundService.findOpenForCommission(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_REFUND_ACTION_NOT_ALLOWED));
        settle(commissionId, offer, offer.getRefundPercent(), null, null, acceptedAt);
    }

    // CANCEL_REFUND: nobody agreed a percentage, so the Admin's is applied, as a refund decided by that Admin. A
    // Commission with nothing held has nothing to refund.
    @Override
    public void settleByAdmin(
        Long commissionId, Long staffId, Integer percent, String reason, OffsetDateTime resolvedAt
    ) {
        if (held(commissionId).isEmpty()) {
            return;
        }

        Commission commission = repository.findById(commissionId).orElseThrow();
        Refund opened = refundService.openForCommission(commissionId, commission.getCustomerId(), reason, percent,
            resolvedAt);
        settle(commissionId, opened, percent, staffId, reason, resolvedAt);
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
        requireParticipant(userId, commission);

        return refundService.listForCommission(commissionId).stream()
            .map(this::toModel)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommissionRefund> listForStaff(RefundStatus status) {
        return refundService.listCommissionRefunds(status).stream()
            .map(this::toModel)
            .toList();
    }

    // Checked under the locks before anything is written: the first installment's share must still be held. The
    // percentage of the share and of the tax half are each rounded half-up to whole VND.
    private void settle(
        Long commissionId, Refund refund, Integer percent, Long staffId, String reason, OffsetDateTime settledAt
    ) {
        Held held = held(commissionId)
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
        log.info("[COMMISSION] - ACTION: settleRefund: commissionId: {}: refundRequestId: {}: percent: {}: status: {}",
            commissionId, refund.getRefundRequestId(), percent, settled.getStatus());
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

    private CommissionRefund toModel(Refund refund) {
        return CommissionRefund.builder()
            .withRefundRequestId(refund.getRefundRequestId())
            .withCommissionId(refund.getCommissionId())
            .withStatus(refund.getStatus())
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

    // Only the Customer and the commissioned Artist see a Commission; to anyone else it does not exist.
    private void requireParticipant(Long userId, Commission commission) {
        if (commission.getCustomerId() == userId) {
            return;
        }

        Artist artist = artistService.get(commission.getArtistId());

        if (!artist.getUserId().equals(userId)) {
            throw new AppException(AppErrorMessage.COMMISSION_NOT_FOUND);
        }
    }

    private record Held(CommissionQuote quote, Payment payment, Proceeds proceeds) {
    }
}
