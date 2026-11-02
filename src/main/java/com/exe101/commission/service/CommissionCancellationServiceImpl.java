package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.commission.mapper.CommissionCancellationEntityMapper;
import com.exe101.commission.mapper.CommissionEntityMapper;
import com.exe101.commission.model.CancellationRequest;
import com.exe101.commission.model.CancellationStatus;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionStatus;
import com.exe101.commission.model.QuoteStatus;
import com.exe101.commission.repository.CancellationRequestRepository;
import com.exe101.commission.repository.CommissionEventRepository;
import com.exe101.commission.repository.CommissionQuoteRepository;
import com.exe101.commission.repository.CommissionRepository;
import com.exe101.commission.repository.QuotePaymentRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.CommissionEvent;
import com.exe101.order.model.PaymentStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Before the first payment either participant cancels at once, and no refund arises (amendment §D.12). After it,
// cancellation is a formal request the other participant accepts or rejects and the requester may withdraw (§24).
// Every action locks the Commission row (UPDLOCK) first, so a racing accept and withdraw, or an accept and the final
// payment, have exactly one winner (§31.9). After the first payment the cancellation carries its refund (F20,
// approved 2 November 2026): the Artist offers a percentage of the held first payment, with their request or in answer
// to the Customer's, and only the Customer's acceptance of that offer cancels the Commission and settles the refund.
// A rejected or withdrawn request closes the offer. A deactivated Artist keeps every action (amendment §A.4).
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CommissionCancellationServiceImpl implements CommissionCancellationService {
    private static int maxTextLength = 5000;
    private static int maxPercent = 100;
    private static int firstInstallment = 1;
    private static Set<CommissionStatus> workStatuses = Set.of(CommissionStatus.IN_PROGRESS,
        CommissionStatus.SUBMITTED_FOR_REVIEW, CommissionStatus.REVISION_REQUESTED);
    private final CommissionRepository repository;
    private final CancellationRequestRepository cancellationRepository;
    private final CommissionQuoteRepository quoteRepository;
    private final QuotePaymentRepository paymentRepository;
    private final CommissionEventRepository eventRepository;
    private final CommissionEntityMapper commissionMapper;
    private final CommissionCancellationEntityMapper mapper;
    private final ArtistService artistService;
    private final CommissionCancellations cancellations;
    private final CommissionRefundService refundService;

    // A Payment still awaited is cancelled with the Commission; a success landing afterwards is an exception.
    @Override
    public Commission cancel(Long userId, Long commissionId, String reason) {
        String text = requireText(reason);
        com.exe101.entity.Commission commission = lock(commissionId);
        isArtist(userId, commission);
        CommissionStatus status = requireOpen(commission);

        if (status == CommissionStatus.DISPUTED) {
            throw new AppException(AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
        }

        if (isPaid(commission, status)) {
            throw new AppException(AppErrorMessage.CANCELLATION_REQUEST_REQUIRED);
        }

        cancellations.cancel(commission, "COMMISSION_CANCELLED", null, userId, null, text, now());
        log.info("[COMMISSION] - ACTION: cancel: commissionId: {}: from: {}", commissionId, status);

        return commissionMapper.toModel(commission);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CancellationRequest> list(Long userId, Long commissionId) {
        com.exe101.entity.Commission commission = repository.findById(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_NOT_FOUND));
        isArtist(userId, commission);

        Integer offer = refundService.findOfferPercent(commissionId).orElse(null);

        return cancellationRepository.findByCommissionIdOrderByCancellationIdAsc(commissionId).stream()
            .map(mapper::toModel)
            .map(request -> request.getStatus() == CancellationStatus.PENDING ? withOffer(request, offer) : request)
            .toList();
    }

    // Only a paid Commission needs agreement; one request may be pending at a time (UX_Cancellation_Pending). The
    // Artist's request carries their refund offer; the Customer's waits for the Artist's.
    @Override
    public CancellationRequest request(Long userId, Long commissionId, String reason, Integer refundPercent) {
        String text = requireText(reason);
        requireValidPercent(refundPercent);
        com.exe101.entity.Commission commission = lock(commissionId);
        boolean artist = isArtist(userId, commission);

        if (artist == (refundPercent == null)) {
            throw new AppException(AppErrorMessage.REFUND_PERCENT_INVALID);
        }

        CommissionStatus status = requireOpen(commission);

        if (status == CommissionStatus.DISPUTED) {
            throw new AppException(AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
        }

        if (cancellationRepository.existsByCommissionIdAndStatus(commissionId, CancellationStatus.PENDING.name())) {
            throw new AppException(AppErrorMessage.CANCELLATION_PENDING);
        }

        if (!isPaid(commission, status)) {
            throw new AppException(AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
        }

        OffsetDateTime requestedAt = now();
        com.exe101.entity.CancellationRequest saved = cancellationRepository.saveAndFlush(
            new com.exe101.entity.CancellationRequest(commissionId, userId, text, CancellationStatus.PENDING.name(),
                requestedAt));
        record(commissionId, "CANCELLATION_REQUESTED", saved.getCancellationId(), userId, null, requestedAt);

        if (artist) {
            refundService.offer(commissionId, userId, refundPercent, text, requestedAt);
        }

        log.info("[COMMISSION] - ACTION: requestCancellation: commissionId: {}: cancellationId: {}", commissionId,
            saved.getCancellationId());

        return withOffer(mapper.toModel(saved), refundPercent);
    }

    // The Artist answers the Customer's request with the refund their terms give for the work done, once.
    @Override
    public CancellationRequest offerRefund(
        Long userId, Long commissionId, Long cancellationId, Integer refundPercent
    ) {
        if (refundPercent == null) {
            throw new AppException(AppErrorMessage.REFUND_PERCENT_INVALID);
        }

        requireValidPercent(refundPercent);
        com.exe101.entity.Commission commission = lock(commissionId);

        if (!isArtist(userId, commission)) {
            throw new AppException(AppErrorMessage.ACCESS_DENIED);
        }

        com.exe101.entity.CancellationRequest request = requirePending(commissionId, cancellationId);

        // The Artist's own request already carries its offer.
        if (request.getRequestedByUserId() == userId) {
            throw new AppException(AppErrorMessage.COMMISSION_REFUND_ACTION_NOT_ALLOWED);
        }

        refundService.offer(commissionId, userId, refundPercent, request.getReason(), now());
        log.info("[COMMISSION] - ACTION: offerRefund: commissionId: {}: cancellationId: {}: percent: {}",
            commissionId, cancellationId, refundPercent);

        return withOffer(mapper.toModel(request), refundPercent);
    }

    // Only the Customer accepts, and only an offer: agreement cancels the Commission (§24.5) and settles the agreed
    // refund in the same transaction (F20). The refund is checked first, so nothing is written if it cannot be paid.
    @Override
    public CancellationRequest accept(Long userId, Long commissionId, Long cancellationId, String note) {
        String text = optionalText(note);
        com.exe101.entity.Commission commission = lock(commissionId);
        boolean artist = isArtist(userId, commission);
        com.exe101.entity.CancellationRequest request = requirePending(commissionId, cancellationId);

        if (artist) {
            requireOtherParty(userId, request);
            throw new AppException(AppErrorMessage.COMMISSION_REFUND_ACTION_NOT_ALLOWED);
        }

        requireOpen(commission);
        Integer refundPercent = refundService.findOfferPercent(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_REFUND_ACTION_NOT_ALLOWED));
        refundService.requireSettleable(commissionId);
        OffsetDateTime acceptedAt = now();
        refundService.settleOffer(commissionId, acceptedAt);
        request.setStatus(CancellationStatus.ACCEPTED.name());
        request.setResolvedAt(acceptedAt);
        com.exe101.entity.CancellationRequest saved = cancellationRepository.saveAndFlush(request);
        cancellations.cancel(commission, "CANCELLATION_ACCEPTED", cancellationId, userId, null, text, acceptedAt);
        log.info("[COMMISSION] - ACTION: acceptCancellation: commissionId: {}: cancellationId: {}: percent: {}",
            commissionId, cancellationId, refundPercent);

        return withOffer(mapper.toModel(saved), refundPercent);
    }

    // A rejected request stays in the history and leaves the Commission as it was (§24.6, §24.9).
    @Override
    public CancellationRequest reject(Long userId, Long commissionId, Long cancellationId, String note) {
        String text = optionalText(note);
        com.exe101.entity.Commission commission = lock(commissionId);
        isArtist(userId, commission);
        com.exe101.entity.CancellationRequest request = requirePending(commissionId, cancellationId);
        requireOtherParty(userId, request);
        OffsetDateTime rejectedAt = now();
        request.setStatus(CancellationStatus.REJECTED.name());
        request.setResolvedAt(rejectedAt);
        com.exe101.entity.CancellationRequest saved = cancellationRepository.saveAndFlush(request);
        record(commissionId, "CANCELLATION_REJECTED", cancellationId, userId, text, rejectedAt);
        refundService.closeOffer(commissionId, "The cancellation request was rejected.", rejectedAt);
        log.info("[COMMISSION] - ACTION: rejectCancellation: commissionId: {}: cancellationId: {}", commissionId,
            cancellationId);

        return mapper.toModel(saved);
    }

    // Only the requester withdraws, and only before the other participant answers (§24.4).
    @Override
    public CancellationRequest withdraw(Long userId, Long commissionId, Long cancellationId) {
        com.exe101.entity.Commission commission = lock(commissionId);
        isArtist(userId, commission);
        com.exe101.entity.CancellationRequest request = requirePending(commissionId, cancellationId);

        if (request.getRequestedByUserId() != userId) {
            throw new AppException(AppErrorMessage.ACCESS_DENIED);
        }

        OffsetDateTime withdrawnAt = now();
        request.setStatus(CancellationStatus.WITHDRAWN.name());
        request.setResolvedAt(withdrawnAt);
        com.exe101.entity.CancellationRequest saved = cancellationRepository.saveAndFlush(request);
        record(commissionId, "CANCELLATION_WITHDRAWN", cancellationId, userId, null, withdrawnAt);
        refundService.closeOffer(commissionId, "The cancellation request was withdrawn.", withdrawnAt);
        log.info("[COMMISSION] - ACTION: withdrawCancellation: commissionId: {}: cancellationId: {}", commissionId,
            cancellationId);

        return mapper.toModel(saved);
    }

    // Paid means the first installment succeeded: the work statuses, or AWAITING_PAYMENT after approval.
    private boolean isPaid(com.exe101.entity.Commission commission, CommissionStatus status) {
        if (workStatuses.contains(status)) {
            return true;
        }

        if (status != CommissionStatus.AWAITING_PAYMENT) {
            return false;
        }

        return quoteRepository.findByCommissionIdAndStatus(commission.getCommissionId(), QuoteStatus.ACCEPTED.name())
            .stream()
            .map(quote -> paymentRepository.findByQuoteIdAndInstallment(quote.getQuoteId(), firstInstallment))
            .flatMap(Optional::stream)
            .anyMatch(payment -> PaymentStatus.SUCCEEDED.name().equals(payment.getStatus()));
    }

    private CancellationRequest withOffer(CancellationRequest request, Integer refundPercent) {
        request.setRefundPercent(refundPercent);

        return request;
    }

    private void requireValidPercent(Integer refundPercent) {
        if (refundPercent != null && (refundPercent < 0 || refundPercent > maxPercent)) {
            throw new AppException(AppErrorMessage.REFUND_PERCENT_INVALID);
        }
    }

    private com.exe101.entity.CancellationRequest requirePending(Long commissionId, Long cancellationId) {
        com.exe101.entity.CancellationRequest request = cancellationRepository
            .findByCancellationIdAndCommissionId(cancellationId, commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.CANCELLATION_REQUEST_NOT_FOUND));

        if (!CancellationStatus.PENDING.name().equals(request.getStatus())) {
            throw new AppException(AppErrorMessage.CANCELLATION_NOT_PENDING);
        }

        return request;
    }

    // The requester cannot answer their own request (§24.3).
    private void requireOtherParty(Long userId, com.exe101.entity.CancellationRequest request) {
        if (request.getRequestedByUserId() == userId) {
            throw new AppException(AppErrorMessage.ACCESS_DENIED);
        }
    }

    private void record(
        Long commissionId, String eventType, Long relatedId, Long userId, String details, OffsetDateTime occurredAt
    ) {
        eventRepository.saveAndFlush(new CommissionEvent(commissionId, eventType, null, null, relatedId, userId, null,
            occurredAt, details));
    }

    private com.exe101.entity.Commission lock(Long commissionId) {
        return repository.findWithLockByCommissionId(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_NOT_FOUND));
    }

    // Only the Customer and the commissioned Artist see a Commission; to anyone else it does not exist.
    private boolean isArtist(Long userId, com.exe101.entity.Commission commission) {
        if (commission.getCustomerId() == userId) {
            return false;
        }

        Artist artist = artistService.get(commission.getArtistId());

        if (!artist.getUserId().equals(userId)) {
            throw new AppException(AppErrorMessage.COMMISSION_NOT_FOUND);
        }

        return true;
    }

    private CommissionStatus requireOpen(com.exe101.entity.Commission commission) {
        CommissionStatus status = CommissionStatus.valueOf(commission.getStatus());

        if (status.isClosed()) {
            throw new AppException(AppErrorMessage.COMMISSION_CLOSED);
        }

        return status;
    }

    private String requireText(String value) {
        String text = value == null ? "" : value.strip();

        if (text.isEmpty() || text.length() > maxTextLength) {
            throw new AppException(AppErrorMessage.COMMISSION_INVALID);
        }

        return text;
    }

    private String optionalText(String value) {
        String text = Optional.ofNullable(value).map(String::strip).orElse("");

        if (text.length() > maxTextLength) {
            throw new AppException(AppErrorMessage.COMMISSION_INVALID);
        }

        return text.isEmpty() ? null : text;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
