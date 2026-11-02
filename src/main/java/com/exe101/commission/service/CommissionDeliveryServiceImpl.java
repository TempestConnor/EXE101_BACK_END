package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.commission.mapper.CommissionDeliverableEntityMapper;
import com.exe101.commission.mapper.CommissionEntityMapper;
import com.exe101.commission.model.CancellationStatus;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionDeliverable;
import com.exe101.commission.model.CommissionStatus;
import com.exe101.commission.model.DeliverableFile;
import com.exe101.commission.model.DeliverableSubmission;
import com.exe101.commission.model.FileUpload;
import com.exe101.commission.model.QuoteSplit;
import com.exe101.commission.model.QuoteStatus;
import com.exe101.commission.model.RevisionRequest;
import com.exe101.commission.repository.CancellationRequestRepository;
import com.exe101.commission.repository.CommissionDeliverableRepository;
import com.exe101.commission.repository.CommissionEventRepository;
import com.exe101.commission.repository.CommissionQuoteRepository;
import com.exe101.commission.repository.CommissionRepository;
import com.exe101.commission.repository.DeliverableFileRepository;
import com.exe101.commission.repository.RevisionRequestRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.CommissionEvent;
import com.exe101.entity.CommissionQuote;
import com.exe101.entity.DeliverableFileId;
import com.exe101.file.model.FileContent;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.service.FileService;
import com.exe101.proceeds.service.ProceedsService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// The Artist submits versions and the Customer approves or asks for a revision (§22). Every action locks the
// Commission row (UPDLOCK) first, so a racing approval and revision request have exactly one winner (§31.4, §31.9).
// Approval returns the Commission to AWAITING_PAYMENT for the final 50%; the first installment is already SUCCEEDED,
// so it cannot be paid again (approved 2 November 2026). The final payment completes the Commission and unlocks the
// approved version's files.
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CommissionDeliveryServiceImpl implements CommissionDeliveryService {
    private static int maxTextLength = 5000;
    private static Set<CommissionStatus> workStatuses =
        Set.of(CommissionStatus.IN_PROGRESS, CommissionStatus.REVISION_REQUESTED);
    private final CommissionRepository repository;
    private final CommissionDeliverableRepository deliverableRepository;
    private final DeliverableFileRepository deliverableFileRepository;
    private final RevisionRequestRepository revisionRepository;
    private final CommissionEventRepository eventRepository;
    private final CommissionQuoteRepository quoteRepository;
    private final CancellationRequestRepository cancellationRepository;
    private final CommissionEntityMapper commissionMapper;
    private final CommissionDeliverableEntityMapper mapper;
    private final ArtistService artistService;
    private final FileService fileService;
    private final ProceedsService proceedsService;

    @Override
    @Transactional(readOnly = true)
    public List<CommissionDeliverable> list(Long userId, Long commissionId) {
        com.exe101.entity.Commission commission = repository.findById(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_NOT_FOUND));
        isArtist(userId, commission);

        return listVersions(commissionId);
    }

    // The Admin reads the versions as evidence when reviewing a dispute (§26.9); the files stay with the
    // participants.
    @Override
    @Transactional(readOnly = true)
    public List<CommissionDeliverable> listForStaff(Long commissionId) {
        return listVersions(commissionId);
    }

    private List<CommissionDeliverable> listVersions(Long commissionId) {
        List<com.exe101.entity.CommissionDeliverable> deliverables =
            deliverableRepository.findByCommissionIdOrderByVersionNoAsc(commissionId);

        if (deliverables.isEmpty()) {
            return List.of();
        }

        List<Long> deliverableIds = deliverables.stream()
            .map(com.exe101.entity.CommissionDeliverable::getDeliverableId)
            .toList();
        List<com.exe101.entity.DeliverableFile> links = deliverableFileRepository.findByIdDeliverableIdIn(deliverableIds);
        Map<Long, StoredFileInfo> files = fileService.describe(links.stream()
                .map(link -> link.getId().getFileId())
                .toList()).stream()
            .collect(Collectors.toMap(StoredFileInfo::getFileId, Function.identity()));
        Map<Long, List<DeliverableFile>> filesByDeliverable = links.stream()
            .collect(Collectors.groupingBy(link -> link.getId().getDeliverableId(),
                Collectors.mapping(link -> mapper.toModel(files.get(link.getId().getFileId())), Collectors.toList())));
        Map<Long, List<RevisionRequest>> revisionsByDeliverable =
            revisionRepository.findByCommissionIdOrderByRevisionIdAsc(commissionId).stream()
                .map(mapper::toModel)
                .collect(Collectors.groupingBy(RevisionRequest::getDeliverableId));

        return deliverables.stream()
            .map(entity -> assemble(entity, filesByDeliverable.getOrDefault(entity.getDeliverableId(), List.of()),
                revisionsByDeliverable.getOrDefault(entity.getDeliverableId(), List.of())))
            .toList();
    }

    // Files are checked before anything is locked or written. A deactivated Artist keeps submitting: work already
    // paid for continues (amendment §A.4).
    @Override
    public CommissionDeliverable submit(Long userId, Long commissionId, DeliverableSubmission submission) {
        String notes = optionalText(submission.getNotes());
        List<FileUpload> uploads = submission.getUploads() == null ? List.of() : submission.getUploads();

        if (uploads.isEmpty()) {
            throw new AppException(AppErrorMessage.COMMISSION_INVALID);
        }

        if (uploads.size() > CommissionFiles.maxFiles) {
            throw new AppException(AppErrorMessage.COMMISSION_ATTACHMENT_LIMIT_REACHED);
        }

        List<NewFile> newFiles = uploads.stream()
            .map(CommissionFiles::toNewFile)
            .toList();
        com.exe101.entity.Commission commission = lock(commissionId);
        requireArtist(userId, commission);
        requireNoPendingCancellation(commissionId);
        CommissionStatus status = requireOpen(commission);

        if (!workStatuses.contains(status)) {
            throw new AppException(AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
        }

        OffsetDateTime submittedAt = now();
        int versionNo = deliverableRepository.findLastVersionNo(commissionId) + 1;
        com.exe101.entity.CommissionDeliverable saved = deliverableRepository.saveAndFlush(
            new com.exe101.entity.CommissionDeliverable(commissionId, versionNo, notes, submittedAt));
        List<DeliverableFile> files = new ArrayList<>();

        for (NewFile newFile : newFiles) {
            StoredFileInfo stored = fileService.storeCommission(newFile);
            deliverableFileRepository.saveAndFlush(new com.exe101.entity.DeliverableFile(
                new DeliverableFileId(saved.getDeliverableId(), stored.getFileId())));
            files.add(mapper.toModel(stored));
        }

        changeStatus(commission, CommissionStatus.SUBMITTED_FOR_REVIEW, "DELIVERABLE_SUBMITTED",
            saved.getDeliverableId(), userId, submittedAt);
        log.info("[COMMISSION] - ACTION: submitDeliverable: commissionId: {}: deliverableId: {}: versionNo: {}",
            commissionId, saved.getDeliverableId(), versionNo);

        return assemble(saved, files, List.of());
    }

    // No Payment is created here: the final 50% is due after approval (amendment §D.13), with no deadline (approved
    // 2 November 2026). A final installment of 0 VND cannot be paid (Payment.Amount > 0), so approval completes such
    // a Commission at once: the Quote is PAID and the first installment is released (approved 2 November 2026).
    @Override
    public Commission approve(Long userId, Long commissionId, Long deliverableId) {
        com.exe101.entity.Commission commission = lock(commissionId);
        requireCustomer(userId, commission);
        requireCurrent(commission, deliverableId);
        CommissionQuote quote = acceptedQuote(commissionId);
        QuoteSplit split = QuoteSplit.of(quote.getPrice(), quote.getTaxAmount());
        OffsetDateTime approvedAt = now();

        if (split.getFinalAmount().signum() == 0) {
            quote.setStatus(QuoteStatus.PAID.name());
            quote.setPaidAt(approvedAt);
            quoteRepository.saveAndFlush(quote);
            commission.setCompletedAt(approvedAt);
            changeStatus(commission, CommissionStatus.COMPLETED, "DELIVERABLE_APPROVED", deliverableId, userId,
                approvedAt);
            proceedsService.releaseQuote(quote.getQuoteId(), approvedAt);
        } else {
            changeStatus(commission, CommissionStatus.AWAITING_PAYMENT, "DELIVERABLE_APPROVED", deliverableId, userId,
                approvedAt);
        }

        log.info("[COMMISSION] - ACTION: approveDeliverable: commissionId: {}: deliverableId: {}: status: {}",
            commissionId, deliverableId, commission.getStatus());

        return commissionMapper.toModel(commission);
    }

    // There is no revision allowance (§22.8); the reviewed version stays as it was (§22.10).
    @Override
    public RevisionRequest requestRevision(Long userId, Long commissionId, Long deliverableId, String details) {
        String text = requireText(details);
        com.exe101.entity.Commission commission = lock(commissionId);
        requireCustomer(userId, commission);
        requireCurrent(commission, deliverableId);
        OffsetDateTime requestedAt = now();
        com.exe101.entity.RevisionRequest saved = revisionRepository.saveAndFlush(
            new com.exe101.entity.RevisionRequest(deliverableId, commissionId, userId, text, requestedAt));
        changeStatus(commission, CommissionStatus.REVISION_REQUESTED, "REVISION_REQUESTED", saved.getRevisionId(),
            userId, requestedAt);
        log.info("[COMMISSION] - ACTION: requestRevision: commissionId: {}: deliverableId: {}: revisionId: {}",
            commissionId, deliverableId, saved.getRevisionId());

        return mapper.toModel(saved);
    }

    // The Customer reviews without downloading (amendment §D.13). The final payment completes the Commission and
    // unlocks the approved version, the latest one; earlier versions stay locked for the Customer (approved
    // 2 November 2026).
    @Override
    public FileContent readFile(Long userId, Long commissionId, Long deliverableId, Long fileId) {
        com.exe101.entity.Commission commission = repository.findById(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_NOT_FOUND));
        boolean artist = isArtist(userId, commission);
        com.exe101.entity.CommissionDeliverable deliverable = deliverableRepository
            .findByDeliverableIdAndCommissionId(deliverableId, commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.DELIVERABLE_NOT_FOUND));

        if (!deliverableFileRepository.existsById(new DeliverableFileId(deliverableId, fileId))) {
            throw new AppException(AppErrorMessage.DELIVERABLE_FILE_NOT_FOUND);
        }

        boolean unlocked = CommissionStatus.COMPLETED.name().equals(commission.getStatus())
            && deliverable.getVersionNo() == deliverableRepository.findLastVersionNo(commissionId);

        if (!artist && !unlocked) {
            throw new AppException(AppErrorMessage.DELIVERABLE_LOCKED);
        }

        return fileService.read(fileId);
    }

    // A Commission under review was paid on its one accepted Quote; change Quotes do not exist yet.
    private CommissionQuote acceptedQuote(Long commissionId) {
        List<CommissionQuote> accepted = quoteRepository.findByCommissionIdAndStatus(commissionId,
            QuoteStatus.ACCEPTED.name());

        if (accepted.size() != 1) {
            throw new IllegalStateException("Expected one accepted Quote: " + commissionId);
        }

        return accepted.getFirst();
    }

    private com.exe101.entity.Commission lock(Long commissionId) {
        return repository.findWithLockByCommissionId(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_NOT_FOUND));
    }

    // Only the version under review can be answered, so an approval of a stale version fails.
    private void requireCurrent(com.exe101.entity.Commission commission, Long deliverableId) {
        com.exe101.entity.CommissionDeliverable deliverable = deliverableRepository
            .findByDeliverableIdAndCommissionId(deliverableId, commission.getCommissionId())
            .orElseThrow(() -> new AppException(AppErrorMessage.DELIVERABLE_NOT_FOUND));
        requireNoPendingCancellation(commission.getCommissionId());
        CommissionStatus status = requireOpen(commission);

        if (status != CommissionStatus.SUBMITTED_FOR_REVIEW) {
            throw new AppException(AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
        }

        if (deliverable.getVersionNo() != deliverableRepository.findLastVersionNo(commission.getCommissionId())) {
            throw new AppException(AppErrorMessage.DELIVERABLE_NOT_CURRENT);
        }
    }

    // While a cancellation request is pending, the formal actions it would conflict with wait for its answer (§24.8).
    private void requireNoPendingCancellation(Long commissionId) {
        if (cancellationRepository.existsByCommissionIdAndStatus(commissionId, CancellationStatus.PENDING.name())) {
            throw new AppException(AppErrorMessage.CANCELLATION_PENDING);
        }
    }

    private void changeStatus(
        com.exe101.entity.Commission commission, CommissionStatus to, String eventType, Long relatedId, Long userId,
        OffsetDateTime occurredAt
    ) {
        String from = commission.getStatus();
        commission.setStatus(to.name());
        repository.saveAndFlush(commission);
        eventRepository.saveAndFlush(new CommissionEvent(commission.getCommissionId(), eventType, from, to.name(),
            relatedId, userId, null, occurredAt, null));
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

    private void requireArtist(Long userId, com.exe101.entity.Commission commission) {
        if (!isArtist(userId, commission)) {
            throw new AppException(AppErrorMessage.ACCESS_DENIED);
        }
    }

    private void requireCustomer(Long userId, com.exe101.entity.Commission commission) {
        if (isArtist(userId, commission)) {
            throw new AppException(AppErrorMessage.ACCESS_DENIED);
        }
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

    private CommissionDeliverable assemble(
        com.exe101.entity.CommissionDeliverable entity, List<DeliverableFile> files, List<RevisionRequest> revisions
    ) {
        CommissionDeliverable deliverable = mapper.toModel(entity);
        deliverable.setFiles(files);
        deliverable.setRevisionRequests(revisions);

        return deliverable;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
