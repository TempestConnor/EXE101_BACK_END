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
// payment, have exactly one winner (§31.9). No money moves: held installments stay HELD for F20 (approved
// 2 November 2026). A deactivated Artist keeps every action (amendment §A.4).
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CommissionCancellationServiceImpl implements CommissionCancellationService {
    private static int maxTextLength = 5000;
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

        return cancellationRepository.findByCommissionIdOrderByCancellationIdAsc(commissionId).stream()
            .map(mapper::toModel)
            .toList();
    }

    // Only a paid Commission needs agreement; one request may be pending at a time (UX_Cancellation_Pending).
    @Override
    public CancellationRequest request(Long userId, Long commissionId, String reason) {
        String text = requireText(reason);
        com.exe101.entity.Commission commission = lock(commissionId);
        isArtist(userId, commission);
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
        log.info("[COMMISSION] - ACTION: requestCancellation: commissionId: {}: cancellationId: {}", commissionId,
            saved.getCancellationId());

        return mapper.toModel(saved);
    }

    // Agreement cancels the Commission without a refund (§24.5, §24.12); the Customer may then request one (F20).
    @Override
    public CancellationRequest accept(Long userId, Long commissionId, Long cancellationId, String note) {
        String text = optionalText(note);
        com.exe101.entity.Commission commission = lock(commissionId);
        isArtist(userId, commission);
        com.exe101.entity.CancellationRequest request = requirePending(commissionId, cancellationId);
        requireOtherParty(userId, request);
        requireOpen(commission);
        OffsetDateTime acceptedAt = now();
        request.setStatus(CancellationStatus.ACCEPTED.name());
        request.setResolvedAt(acceptedAt);
        com.exe101.entity.CancellationRequest saved = cancellationRepository.saveAndFlush(request);
        cancellations.cancel(commission, "CANCELLATION_ACCEPTED", cancellationId, userId, null, text, acceptedAt);
        log.info("[COMMISSION] - ACTION: acceptCancellation: commissionId: {}: cancellationId: {}", commissionId,
            cancellationId);

        return mapper.toModel(saved);
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
