package com.exe101.proceeds.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.commission.model.QuoteSettlement;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.persistence.DatabaseErrors;
import com.exe101.entity.ArtistProceeds;
import com.exe101.entity.Payout;
import com.exe101.entity.PayoutLine;
import com.exe101.ledger.model.ArtistMovement;
import com.exe101.ledger.service.LedgerService;
import com.exe101.listing.model.ListingType;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderLine;
import com.exe101.order.model.OrderStatus;
import com.exe101.proceeds.mapper.ProceedsEntityMapper;
import com.exe101.proceeds.model.Proceeds;
import com.exe101.proceeds.model.ProceedsStatus;
import com.exe101.proceeds.model.Release;
import com.exe101.proceeds.model.ReleaseOutcome;
import com.exe101.proceeds.model.ReleaseStatus;
import com.exe101.proceeds.repository.ProceedsRepository;
import com.exe101.proceeds.repository.ReleaseLineRepository;
import com.exe101.proceeds.repository.ReleaseRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
public class ProceedsServiceImpl implements ProceedsService {
    // The Admin records each release's outcome; PAID and FAILED are final (approved 1 November 2026).
    private static Map<ReleaseStatus, Set<ReleaseStatus>> allowedOutcomes = Map.of(
        ReleaseStatus.PENDING, Set.of(ReleaseStatus.PROCESSING, ReleaseStatus.PAID, ReleaseStatus.FAILED),
        ReleaseStatus.PROCESSING, Set.of(ReleaseStatus.PAID, ReleaseStatus.FAILED));
    private static String currency = "VND";
    private static String transferProvider = "MANUAL";
    private static String releaseCreatedAction = "RELEASE_CREATED";
    private static String releaseOutcomeAction = "RELEASE_STATUS_CHANGED";
    private static String releaseTarget = "PAYOUT";
    private final ProceedsRepository repository;
    private final ReleaseRepository releaseRepository;
    private final ReleaseLineRepository lineRepository;
    private final ProceedsEntityMapper mapper;
    private final ArtistService artistService;
    private final AuditService auditService;
    private final LedgerService ledgerService;

    // §14.8, Add.3: each paid item holds its snapshotted proceeds for its own Artist, never recalculated (§7.13). A
    // direct digital item reaches its milestone with the payment (amendment §B.7). Runs in the payment-success
    // transaction, which holds the Order lock; an item that already has proceeds is skipped (§31.11), and
    // UX_Proceeds_OrderItem is the database guard.
    @Override
    public List<Proceeds> recordPaid(Order order) {
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("Order is not paid: " + order.getOrderId());
        }

        List<Proceeds> recorded = order.getItems().stream()
            .filter(line -> !repository.existsByOrderItemId(line.getOrderItemId()))
            .map(line -> record(line, order.getPaidAt()))
            .toList();
        log.info("[PROCEEDS] - ACTION: recordPaid: orderId: {}: recorded: {}", order.getOrderId(), recorded.size());

        return recorded;
    }

    // Amendment §D.13: a paid Quote installment holds its share of the price for the commissioned Artist (fee 0)
    // until the Commission's release milestone. Runs in the payment-success transaction, which holds the Commission
    // lock; an installment that already has proceeds is skipped (§31.11), and UX_Proceeds_QuoteInstallment is the
    // database guard.
    @Override
    public Optional<Proceeds> holdQuote(QuoteSettlement settlement) {
        if (repository.existsByQuoteIdAndInstallment(settlement.getQuoteId(), settlement.getInstallment())) {
            return Optional.empty();
        }

        Proceeds proceeds = Proceeds.builder()
            .withArtistId(settlement.getArtistId())
            .withQuoteId(settlement.getQuoteId())
            .withInstallment(settlement.getInstallment())
            .withAmount(settlement.getProceeds())
            .withCurrencyCode(currency)
            .withStatus(ProceedsStatus.HELD)
            .build();
        Proceeds held = mapper.toModel(repository.saveAndFlush(mapper.toEntity(proceeds)));
        log.info("[PROCEEDS] - ACTION: holdQuote: quoteId: {}: installment: {}: proceedsId: {}",
            settlement.getQuoteId(), settlement.getInstallment(), held.getProceedsId());

        return Optional.of(held);
    }

    // Amendment §B.7: a physical item's proceeds are released when its return window closes with no open or approved
    // return. The caller holds the Order lock and has checked the window; proceeds already released are skipped.
    @Override
    public Optional<Proceeds> releasePhysical(Long orderItemId) {
        Optional<ArtistProceeds> held = repository.findWithLockByOrderItemId(orderItemId)
            .filter(entity -> ProceedsStatus.HELD.name().equals(entity.getStatus()));
        Optional<Proceeds> released = held.map(entity -> mapper.toModel(reachMilestone(entity, now())));
        released.ifPresent(proceeds -> log.info("[PROCEEDS] - ACTION: releasePhysical: orderItemId: {}: status: {}",
            orderItemId, proceeds.getStatus()));

        return released;
    }

    // For a refund decision, which holds the Order and item locks: the proceeds are locked last, as at the window
    // close.
    @Override
    public Optional<Proceeds> findLockedByOrderItem(Long orderItemId) {
        return repository.findWithLockByOrderItemId(orderItemId).map(mapper::toModel);
    }

    // Amendment §B.8: a refund takes back proceeds only while they are still held. The caller has checked that under
    // the same locks; the ledger reversal is part of the refund's own posting.
    @Override
    public Proceeds voidHeld(Long orderItemId) {
        ArtistProceeds entity = repository.findWithLockByOrderItemId(orderItemId)
            .filter(found -> ProceedsStatus.HELD.name().equals(found.getStatus()))
            .orElseThrow(() -> new IllegalStateException("No held proceeds to void: " + orderItemId));
        entity.setStatus(ProceedsStatus.VOID.name());
        Proceeds voided = mapper.toModel(repository.saveAndFlush(entity));
        log.info("[PROCEEDS] - ACTION: voidHeld: orderItemId: {}: proceedsId: {}", orderItemId,
            voided.getProceedsId());

        return voided;
    }

    // A failed release leaves its proceeds ELIGIBLE; the Admin starts a new one for them, but only while the ledger
    // and the records agree.
    @Override
    public Release release(Long staffId, Long proceedsId) {
        ledgerService.requireReconciled();
        ArtistProceeds entity = repository.findWithLockByProceedsId(proceedsId)
            .orElseThrow(() -> new AppException(AppErrorMessage.PROCEEDS_NOT_FOUND));

        if (!ProceedsStatus.ELIGIBLE.name().equals(entity.getStatus())) {
            throw new AppException(AppErrorMessage.PROCEEDS_NOT_RELEASABLE);
        }

        Payout payout = reserve(entity, now());
        audit(staffId, releaseCreatedAction, payout.getPayoutId(), null,
            "proceedsId=" + proceedsId + ";amount=" + payout.getAmount().toPlainString());
        log.info("[PROCEEDS] - ACTION: release: staffId: {}: proceedsId: {}: payoutId: {}", staffId, proceedsId,
            payout.getPayoutId());

        return mapper.toModel(payout, proceedsId);
    }

    // The reference check comes first, so a bad request locks nothing. Money may start leaving (PROCESSING, PAID)
    // only while the ledger and the records agree; FAILED keeps the money in and is always allowed. Then the
    // release is locked before its proceeds, so two outcomes for one release wait for each other and the second
    // finds it settled.
    @Override
    public Release recordOutcome(Long staffId, Long payoutId, ReleaseOutcome outcome) {
        ReleaseStatus target = outcome.getStatus();
        String reference = blankToNull(outcome.getTransferReference());

        if ((target == ReleaseStatus.PAID) != (reference != null)) {
            throw new AppException(AppErrorMessage.RELEASE_REFERENCE_INVALID);
        }

        if (target != ReleaseStatus.FAILED) {
            ledgerService.requireReconciled();
        }

        Payout payout = releaseRepository.findWithLockByPayoutId(payoutId)
            .orElseThrow(() -> new AppException(AppErrorMessage.RELEASE_NOT_FOUND));
        ReleaseStatus current = ReleaseStatus.valueOf(payout.getStatus());

        if (!allowedOutcomes.getOrDefault(current, Set.of()).contains(target)) {
            throw new AppException(AppErrorMessage.RELEASE_STATUS_CHANGE_NOT_ALLOWED);
        }

        PayoutLine line = lineRepository.findByPayoutIdAndIsActiveTrue(payoutId)
            .orElseThrow(() -> new IllegalStateException("Open release without an active line: " + payoutId));
        ArtistProceeds proceeds = repository.findWithLockByProceedsId(line.getProceedsId()).orElseThrow();

        if (!ProceedsStatus.RESERVED.name().equals(proceeds.getStatus())) {
            throw new IllegalStateException("Open release for proceeds that are not reserved: " + payoutId);
        }

        OffsetDateTime changedAt = now();
        payout.setStatus(target.name());

        if (target == ReleaseStatus.PAID) {
            payout.setProviderName(transferProvider);
            payout.setProviderPayoutRef(reference);
            payout.setPaidAt(changedAt);
            proceeds.setStatus(ProceedsStatus.PAID.name());
            proceeds.setPaidAt(changedAt);
        }

        // The line is closed, so the proceeds can be released again without two active lines (UX_PayoutLine_Active).
        if (target == ReleaseStatus.FAILED) {
            line.setIsActive(false);
            lineRepository.saveAndFlush(line);
            proceeds.setStatus(ProceedsStatus.ELIGIBLE.name());
        }

        Payout saved = saveRelease(payout);
        repository.saveAndFlush(proceeds);

        if (target == ReleaseStatus.PAID) {
            ledgerService.recordReleasePaid(movement(payoutId, proceeds, changedAt));
        }

        if (target == ReleaseStatus.FAILED) {
            ledgerService.recordReleaseFailed(movement(payoutId, proceeds, changedAt));
        }

        audit(staffId, releaseOutcomeAction, payoutId, blankToNull(outcome.getReason()),
            "from=" + current + ";to=" + target + ";proceedsId=" + proceeds.getProceedsId()
                + (reference == null ? "" : ";transferReference=" + reference));
        log.info("[PROCEEDS] - ACTION: recordOutcome: staffId: {}: payoutId: {}: status: {}", staffId, payoutId,
            target);

        return mapper.toModel(saved, proceeds.getProceedsId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Release> list(ReleaseStatus status) {
        List<Payout> payouts = status == null
            ? releaseRepository.findAllByOrderByCreatedAtDescPayoutIdDesc()
            : releaseRepository.findByStatusOrderByCreatedAtDescPayoutIdDesc(status.name());

        return releases(payouts);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Proceeds> listOwn(Long userId) {
        Artist artist = artistService.getByUserId(userId);

        return repository.findByArtistIdOrderByProceedsIdDesc(artist.getArtistId()).stream()
            .map(mapper::toModel)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Release> listOwnReleases(Long userId) {
        Artist artist = artistService.getByUserId(userId);

        return releases(releaseRepository.findByArtistIdOrderByCreatedAtDescPayoutIdDesc(artist.getArtistId()));
    }

    private Proceeds record(OrderLine line, OffsetDateTime paidAt) {
        Proceeds proceeds = Proceeds.builder()
            .withArtistId(line.getArtistId())
            .withOrderItemId(line.getOrderItemId())
            .withAmount(line.getArtistProceeds())
            .withCurrencyCode(line.getCurrencyCode())
            .withStatus(ProceedsStatus.HELD)
            .build();
        ArtistProceeds saved = repository.saveAndFlush(mapper.toEntity(proceeds));

        if (line.getListingType() == ListingType.DIGITAL) {
            saved = reachMilestone(saved, paidAt);
        }

        return mapper.toModel(saved);
    }

    // The proceeds become eligible and are released at once, one release per item (approved 1 November 2026).
    private ArtistProceeds reachMilestone(ArtistProceeds entity, OffsetDateTime reachedAt) {
        entity.setEligibleAt(reachedAt);

        // Nothing to transfer: 0 VND proceeds are settled at their milestone without a release, which must be > 0.
        if (entity.getAmount().signum() == 0) {
            entity.setStatus(ProceedsStatus.PAID.name());
            entity.setPaidAt(reachedAt);

            return repository.saveAndFlush(entity);
        }

        entity.setStatus(ProceedsStatus.ELIGIBLE.name());
        ledgerService.recordProceedsReleased(movement(entity.getProceedsId(), entity, reachedAt));
        reserve(entity, reachedAt);

        return entity;
    }

    private Payout reserve(ArtistProceeds entity, OffsetDateTime createdAt) {
        Release release = Release.builder()
            .withArtistId(entity.getArtistId())
            .withAmount(entity.getAmount())
            .withCurrencyCode(entity.getCurrencyCode())
            .withStatus(ReleaseStatus.PENDING)
            .withCreatedAt(createdAt)
            .build();
        Payout payout = releaseRepository.saveAndFlush(mapper.toEntity(release));
        lineRepository.saveAndFlush(mapper.toEntity(payout.getPayoutId(), entity.getProceedsId(),
            entity.getAmount()));
        entity.setStatus(ProceedsStatus.RESERVED.name());
        repository.saveAndFlush(entity);
        ledgerService.recordReleaseCreated(movement(payout.getPayoutId(), entity, createdAt));

        return payout;
    }

    private ArtistMovement movement(Long sourceId, ArtistProceeds proceeds, OffsetDateTime occurredAt) {
        return ArtistMovement.builder()
            .withSourceId(sourceId)
            .withArtistId(proceeds.getArtistId())
            .withOrderItemId(proceeds.getOrderItemId())
            .withAmount(proceeds.getAmount())
            .withOccurredAt(occurredAt)
            .build();
    }

    private Payout saveRelease(Payout payout) {
        try {
            return releaseRepository.saveAndFlush(payout);
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseErrors.isDuplicateKey(exception)) {
                throw new AppException(AppErrorMessage.RELEASE_REFERENCE_IN_USE);
            }

            throw exception;
        }
    }

    // Each release carries one line; a failed release keeps its inactive line, so it still names its proceeds.
    private List<Release> releases(List<Payout> payouts) {
        if (payouts.isEmpty()) {
            return List.of();
        }

        List<Long> payoutIds = payouts.stream().map(Payout::getPayoutId).toList();
        Map<Long, Long> proceedsIds = lineRepository.findByPayoutIdIn(payoutIds).stream()
            .collect(Collectors.toMap(PayoutLine::getPayoutId, PayoutLine::getProceedsId));

        return payouts.stream()
            .map(payout -> mapper.toModel(payout, proceedsIds.get(payout.getPayoutId())))
            .toList();
    }

    private void audit(Long staffId, String actionType, Long payoutId, String reason, String details) {
        AuditEntry entry = AuditEntry.builder()
            .withStaffId(staffId)
            .withActionType(actionType)
            .withTargetType(releaseTarget)
            .withTargetId(payoutId)
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
