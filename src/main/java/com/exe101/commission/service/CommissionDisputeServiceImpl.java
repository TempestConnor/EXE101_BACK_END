package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.commission.mapper.CommissionCancellationEntityMapper;
import com.exe101.commission.mapper.CommissionEntityMapper;
import com.exe101.commission.mapper.CommissionQuoteEntityMapper;
import com.exe101.commission.model.CancellationStatus;
import com.exe101.commission.model.CommissionDispute;
import com.exe101.commission.model.CommissionStatus;
import com.exe101.commission.model.DisputeEvidence;
import com.exe101.commission.model.DisputeOutcome;
import com.exe101.commission.model.DisputeResolution;
import com.exe101.commission.model.DisputeStatus;
import com.exe101.commission.repository.CancellationRequestRepository;
import com.exe101.commission.repository.CommissionDisputeRepository;
import com.exe101.commission.repository.CommissionEventRepository;
import com.exe101.commission.repository.CommissionMessageRepository;
import com.exe101.commission.repository.CommissionQuoteRepository;
import com.exe101.commission.repository.CommissionRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.persistence.DatabaseErrors;
import com.exe101.entity.CommissionEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Either participant opens a dispute; the Commission becomes DISPUTED, which refuses every formal action and new
// payment attempt until the Admin resolves it (approved 2 November 2026). The Admin takes it under review and resolves
// it with an outcome and a reason, each audited in the same transaction (§26, §29). CONTINUE restores the status the
// dispute interrupted. Both cancelling outcomes cancel the Commission: CANCEL_REFUND opens its refund, and
// CANCEL_NO_REFUND releases the held installments to the Artist (F20, approved 2 November 2026).
// Every action locks the Commission row first, and UX_Dispute_Active backs the one-active-dispute rule (§31.8).
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CommissionDisputeServiceImpl implements CommissionDisputeService {
    private static int maxTextLength = 5000;
    private static int maxStaffReasonLength = 2000;
    private static String openedEvent = "DISPUTE_OPENED";
    private static String resolvedEvent = "DISPUTE_RESOLVED";
    private static String reviewAction = "COMMISSION_DISPUTE_REVIEW_STARTED";
    private static String resolveAction = "COMMISSION_DISPUTE_RESOLVED";
    private static String disputeTarget = "COMMISSION_DISPUTE";
    private final CommissionRepository repository;
    private final CommissionDisputeRepository disputeRepository;
    private final CancellationRequestRepository cancellationRepository;
    private final CommissionMessageRepository messageRepository;
    private final CommissionQuoteRepository quoteRepository;
    private final CommissionEventRepository eventRepository;
    private final CommissionEntityMapper commissionMapper;
    private final CommissionQuoteEntityMapper quoteMapper;
    private final CommissionCancellationEntityMapper mapper;
    private final CommissionDeliveryService deliveryService;
    private final ArtistService artistService;
    private final AuditService auditService;
    private final CommissionCancellations cancellations;
    private final CommissionRefundService refundService;

    // A pending cancellation is answered or withdrawn first, so no request is left pending under a dispute.
    @Override
    public CommissionDispute open(Long userId, Long commissionId, String reason) {
        String text = requireText(reason);
        com.exe101.entity.Commission commission = lock(commissionId);
        requireParticipant(userId, commission);
        CommissionStatus status = CommissionStatus.valueOf(commission.getStatus());

        if (status.isClosed()) {
            throw new AppException(AppErrorMessage.COMMISSION_CLOSED);
        }

        if (status == CommissionStatus.DISPUTED) {
            throw new AppException(AppErrorMessage.DISPUTE_ALREADY_OPEN);
        }

        if (cancellationRepository.existsByCommissionIdAndStatus(commissionId, CancellationStatus.PENDING.name())) {
            throw new AppException(AppErrorMessage.CANCELLATION_PENDING);
        }

        OffsetDateTime openedAt = now();
        com.exe101.entity.CommissionDispute saved = save(new com.exe101.entity.CommissionDispute(commissionId, userId,
            text, DisputeStatus.OPEN.name(), openedAt));
        // The event keeps the interrupted status, which CONTINUE restores.
        changeStatus(commission, CommissionStatus.DISPUTED.name(), openedEvent, saved.getDisputeId(), userId, null,
            null, openedAt);
        log.info("[COMMISSION] - ACTION: openDispute: commissionId: {}: disputeId: {}: from: {}", commissionId,
            saved.getDisputeId(), status);

        return mapper.toModel(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommissionDispute> list(Long userId, Long commissionId) {
        com.exe101.entity.Commission commission = repository.findById(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_NOT_FOUND));
        requireParticipant(userId, commission);

        return disputeRepository.findByCommissionIdOrderByDisputeIdAsc(commissionId).stream()
            .map(this::toModel)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommissionDispute> listForStaff(DisputeStatus status) {
        List<com.exe101.entity.CommissionDispute> disputes = status == null
            ? disputeRepository.findAllByOrderByDisputeIdAsc()
            : disputeRepository.findByStatusOrderByDisputeIdAsc(status.name());

        return disputes.stream()
            .map(this::toModel)
            .toList();
    }

    // Messages, Quotes, versions and cancellation requests stay available for review (§26.9).
    @Override
    @Transactional(readOnly = true)
    public DisputeEvidence evidence(Long disputeId) {
        com.exe101.entity.CommissionDispute dispute = disputeRepository.findById(disputeId)
            .orElseThrow(() -> new AppException(AppErrorMessage.DISPUTE_NOT_FOUND));
        long commissionId = dispute.getCommissionId();
        com.exe101.entity.Commission commission = repository.findById(commissionId).orElseThrow();

        return DisputeEvidence.builder()
            .withDispute(toModel(dispute))
            .withCommission(commissionMapper.toModel(commission))
            .withMessages(messageRepository.findByCommissionIdOrderByMessageIdAsc(commissionId).stream()
                .map(commissionMapper::toModel)
                .toList())
            .withQuotes(quoteRepository.findByCommissionIdOrderBySequenceNoAsc(commissionId).stream()
                .map(quoteMapper::toModel)
                .toList())
            .withDeliverables(deliveryService.listForStaff(commissionId))
            .withCancellationRequests(cancellationRepository.findByCommissionIdOrderByCancellationIdAsc(commissionId)
                .stream()
                .map(mapper::toModel)
                .toList())
            .build();
    }

    @Override
    public CommissionDispute review(Long staffId, Long disputeId) {
        com.exe101.entity.Commission commission = lockForDispute(disputeId);
        com.exe101.entity.CommissionDispute dispute = disputeRepository.findById(disputeId).orElseThrow();

        if (!DisputeStatus.OPEN.name().equals(dispute.getStatus())) {
            throw new AppException(AppErrorMessage.DISPUTE_ACTION_NOT_ALLOWED);
        }

        OffsetDateTime reviewedAt = now();
        dispute.setStatus(DisputeStatus.UNDER_REVIEW.name());
        com.exe101.entity.CommissionDispute saved = disputeRepository.saveAndFlush(dispute);
        eventRepository.saveAndFlush(new CommissionEvent(commission.getCommissionId(), "DISPUTE_UNDER_REVIEW", null,
            null, disputeId, null, staffId, reviewedAt, null));
        auditService.record(AuditEntry.builder()
            .withStaffId(staffId)
            .withActionType(reviewAction)
            .withTargetType(disputeTarget)
            .withTargetId(disputeId)
            .withDetails("commissionId=" + commission.getCommissionId())
            .build());
        log.info("[COMMISSION] - ACTION: reviewDispute: commissionId: {}: disputeId: {}", commission.getCommissionId(),
            disputeId);

        return toModel(saved);
    }

    // A dispute is resolved once, from UNDER_REVIEW (§31.9).
    @Override
    public CommissionDispute resolve(Long staffId, Long disputeId, DisputeResolution resolution) {
        DisputeOutcome outcome = resolution == null ? null : resolution.getOutcome();
        String reason = resolution == null || resolution.getReason() == null ? "" : resolution.getReason().strip();

        if (outcome == null || reason.isEmpty() || reason.length() > maxStaffReasonLength) {
            throw new AppException(AppErrorMessage.DISPUTE_RESOLUTION_INVALID);
        }

        com.exe101.entity.Commission commission = lockForDispute(disputeId);
        com.exe101.entity.CommissionDispute dispute = disputeRepository.findById(disputeId).orElseThrow();

        if (!DisputeStatus.UNDER_REVIEW.name().equals(dispute.getStatus())) {
            throw new AppException(AppErrorMessage.DISPUTE_ACTION_NOT_ALLOWED);
        }

        long commissionId = commission.getCommissionId();
        OffsetDateTime resolvedAt = now();
        dispute.setStatus(DisputeStatus.RESOLVED.name());
        dispute.setOutcome(outcome.name());
        dispute.setDecidedByStaffId(staffId);
        dispute.setResolvedAt(resolvedAt);
        com.exe101.entity.CommissionDispute saved = disputeRepository.saveAndFlush(dispute);
        String from = commission.getStatus();

        if (outcome == DisputeOutcome.CONTINUE) {
            String previous = eventRepository
                .findFirstByCommissionIdAndEventTypeAndRelatedIdOrderByEventIdDesc(commissionId, openedEvent, disputeId)
                .map(CommissionEvent::getFromStatus)
                .orElseThrow(() -> new IllegalStateException("Dispute has no opening event: " + disputeId));
            changeStatus(commission, previous, resolvedEvent, disputeId, null, staffId, reason, resolvedAt);
        } else {
            cancellations.cancel(commission, resolvedEvent, disputeId, null, staffId, reason, resolvedAt);

            if (outcome == DisputeOutcome.CANCEL_REFUND) {
                refundService.openOnCancellation(commissionId, null, staffId, reason, resolvedAt);
            } else {
                refundService.releaseOnCancellation(commissionId, resolvedAt);
            }
        }

        auditService.record(AuditEntry.builder()
            .withStaffId(staffId)
            .withActionType(resolveAction)
            .withTargetType(disputeTarget)
            .withTargetId(disputeId)
            .withReason(reason)
            .withDetails("commissionId=" + commissionId + ";outcome=" + outcome + ";from=" + from + ";to="
                + commission.getStatus())
            .build());
        log.info("[COMMISSION] - ACTION: resolveDispute: commissionId: {}: disputeId: {}: outcome: {}: status: {}",
            commissionId, disputeId, outcome, commission.getStatus());
        CommissionDispute resolved = mapper.toModel(saved);
        resolved.setResolutionReason(reason);

        return resolved;
    }

    // The Commission is locked before the dispute is read, in the same order as the participants' actions.
    private com.exe101.entity.Commission lockForDispute(Long disputeId) {
        Long commissionId = disputeRepository.findCommissionIdByDisputeId(disputeId)
            .orElseThrow(() -> new AppException(AppErrorMessage.DISPUTE_NOT_FOUND));

        return repository.findWithLockByCommissionId(commissionId).orElseThrow();
    }

    private com.exe101.entity.CommissionDispute save(com.exe101.entity.CommissionDispute dispute) {
        try {
            return disputeRepository.saveAndFlush(dispute);
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseErrors.isDuplicateKey(exception)) {
                throw new AppException(AppErrorMessage.DISPUTE_ALREADY_OPEN);
            }

            throw exception;
        }
    }

    private void changeStatus(
        com.exe101.entity.Commission commission, String to, String eventType, Long relatedId, Long userId,
        Long staffId, String details, OffsetDateTime occurredAt
    ) {
        String from = commission.getStatus();
        commission.setStatus(to);
        repository.saveAndFlush(commission);
        eventRepository.saveAndFlush(new CommissionEvent(commission.getCommissionId(), eventType, from, to,
            relatedId, userId, staffId, occurredAt, details));
    }

    // The Admin's reason is kept on the resolving event and in the audit record.
    private CommissionDispute toModel(com.exe101.entity.CommissionDispute entity) {
        CommissionDispute dispute = mapper.toModel(entity);

        if (dispute.getStatus() == DisputeStatus.RESOLVED) {
            dispute.setResolutionReason(eventRepository
                .findFirstByCommissionIdAndEventTypeAndRelatedIdOrderByEventIdDesc(entity.getCommissionId(),
                    resolvedEvent, entity.getDisputeId())
                .map(CommissionEvent::getDetails)
                .orElse(null));
        }

        return dispute;
    }

    private com.exe101.entity.Commission lock(Long commissionId) {
        return repository.findWithLockByCommissionId(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_NOT_FOUND));
    }

    // Only the Customer and the commissioned Artist see a Commission; to anyone else it does not exist.
    private void requireParticipant(Long userId, com.exe101.entity.Commission commission) {
        if (commission.getCustomerId() == userId) {
            return;
        }

        Artist artist = artistService.get(commission.getArtistId());

        if (!artist.getUserId().equals(userId)) {
            throw new AppException(AppErrorMessage.COMMISSION_NOT_FOUND);
        }
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
}
