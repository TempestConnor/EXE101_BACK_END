package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.commission.mapper.CommissionEntityMapper;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionAttachment;
import com.exe101.commission.model.CommissionMessage;
import com.exe101.commission.model.CommissionRequest;
import com.exe101.commission.model.CommissionStatus;
import com.exe101.commission.model.FileUpload;
import com.exe101.commission.repository.CommissionAttachmentRepository;
import com.exe101.commission.repository.CommissionEventRepository;
import com.exe101.commission.repository.CommissionMessageRepository;
import com.exe101.commission.repository.CommissionRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.Customer;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.CommissionEvent;
import com.exe101.file.model.FileContent;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.service.FileService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CommissionServiceImpl implements CommissionService {
    private static String activeArtist = "ACTIVE";
    private static String requestedEvent = "REQUESTED";
    private static int maxTextLength = 5000;
    private static int maxAttachments = 10;
    private static long maxAttachmentBytes = 20L * 1024 * 1024;
    private final CommissionRepository repository;
    private final CommissionMessageRepository messageRepository;
    private final CommissionAttachmentRepository attachmentRepository;
    private final CommissionEventRepository eventRepository;
    private final CommissionEntityMapper mapper;
    private final ArtistService artistService;
    private final CustomerService customerService;
    private final FileService fileService;

    // Files are checked before anything is locked or written. The Artist row is then read under a shared lock, so a
    // deactivation either commits before the check or waits for this request to commit (§17.4, §17.9).
    @Override
    public Commission request(Long userId, CommissionRequest request) {
        String description = requireText(request.getDescription());
        List<FileUpload> uploads = request.getUploads() == null ? List.of() : request.getUploads();

        if (uploads.size() > maxAttachments) {
            throw new AppException(AppErrorMessage.COMMISSION_ATTACHMENT_LIMIT_REACHED);
        }

        List<NewFile> files = uploads.stream()
            .map(this::toNewFile)
            .toList();
        Customer customer = customerService.getActive(userId);
        Artist artist = artistService.lockForCommission(request.getArtistId());

        if (artist.getUserId().equals(userId)) {
            throw new AppException(AppErrorMessage.OWN_ARTIST_PROFILE);
        }

        if (!activeArtist.equals(artist.getStatus())) {
            throw new AppException(AppErrorMessage.ARTIST_NOT_ACTIVE);
        }

        if (!artist.isAcceptingCommissions()) {
            throw new AppException(AppErrorMessage.ARTIST_NOT_ACCEPTING_COMMISSIONS);
        }

        OffsetDateTime createdAt = now();
        com.exe101.entity.Commission entity = new com.exe101.entity.Commission(userId, artist.getArtistId(),
            description, CommissionStatus.REQUESTED.name(), customer.getDisplayName(), artist.getDisplayName(),
            createdAt, null, null);
        entity.setAllowPublicDisplay(request.isAllowPublicDisplay());
        com.exe101.entity.Commission saved = repository.saveAndFlush(entity);
        List<CommissionAttachment> attachments = new ArrayList<>();

        for (NewFile file : files) {
            attachments.add(attach(saved.getCommissionId(), userId, file));
        }

        eventRepository.saveAndFlush(new CommissionEvent(saved.getCommissionId(), requestedEvent, null,
            CommissionStatus.REQUESTED.name(), null, userId, null, createdAt, null));
        log.info("[COMMISSION] - ACTION: request: commissionId: {}: artistId: {}", saved.getCommissionId(),
            artist.getArtistId());

        return assemble(saved, attachments);
    }

    @Override
    public List<Commission> listMine(Long userId) {
        return repository.findForParticipant(userId).stream()
            .map(entity -> assemble(entity, List.of()))
            .toList();
    }

    @Override
    public Commission get(Long userId, Long commissionId) {
        com.exe101.entity.Commission entity = requireParticipant(userId, repository.findById(commissionId));
        List<com.exe101.entity.CommissionAttachment> links =
            attachmentRepository.findByCommissionIdOrderByAttachmentIdAsc(commissionId);
        List<Long> fileIds = links.stream()
            .map(com.exe101.entity.CommissionAttachment::getFileId)
            .toList();
        Map<Long, StoredFileInfo> files = fileService.describe(fileIds).stream()
            .collect(Collectors.toMap(StoredFileInfo::getFileId, Function.identity()));
        List<CommissionAttachment> attachments = links.stream()
            .map(link -> mapper.toModel(link, files.get(link.getFileId())))
            .toList();

        return assemble(entity, attachments);
    }

    @Override
    public List<CommissionMessage> listMessages(Long userId, Long commissionId) {
        requireParticipant(userId, repository.findById(commissionId));

        return messageRepository.findByCommissionIdOrderByMessageIdAsc(commissionId).stream()
            .map(mapper::toModel)
            .toList();
    }

    // Messages never change terms or status (§18.3–4) and cannot be edited (§18.5; TR_Message_Immutable). A
    // deactivated Artist keeps messaging on existing Commissions (§17.9).
    @Override
    public CommissionMessage sendMessage(Long userId, Long commissionId, String body) {
        String text = requireText(body);
        com.exe101.entity.Commission entity = requireParticipant(userId,
            repository.findWithSharedLockByCommissionId(commissionId));
        requireOpen(entity);
        com.exe101.entity.CommissionMessage saved = messageRepository.saveAndFlush(
            new com.exe101.entity.CommissionMessage(commissionId, userId, text, now()));
        log.info("[COMMISSION] - ACTION: sendMessage: commissionId: {}: messageId: {}", commissionId,
            saved.getMessageId());

        return mapper.toModel(saved);
    }

    @Override
    public CommissionAttachment addAttachment(Long userId, Long commissionId, FileUpload upload) {
        NewFile file = toNewFile(upload);
        com.exe101.entity.Commission entity = requireParticipant(userId,
            repository.findWithLockByCommissionId(commissionId));
        requireOpen(entity);

        if (attachmentRepository.countByCommissionId(commissionId) >= maxAttachments) {
            throw new AppException(AppErrorMessage.COMMISSION_ATTACHMENT_LIMIT_REACHED);
        }

        CommissionAttachment attachment = attach(commissionId, userId, file);
        log.info("[COMMISSION] - ACTION: addAttachment: commissionId: {}: attachmentId: {}", commissionId,
            attachment.getAttachmentId());

        return attachment;
    }

    @Override
    public FileContent readAttachment(Long userId, Long commissionId, Long attachmentId) {
        requireParticipant(userId, repository.findById(commissionId));
        com.exe101.entity.CommissionAttachment attachment = attachmentRepository
            .findByAttachmentIdAndCommissionId(attachmentId, commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_ATTACHMENT_NOT_FOUND));

        return fileService.read(attachment.getFileId());
    }

    private CommissionAttachment attach(Long commissionId, Long userId, NewFile file) {
        StoredFileInfo stored = fileService.storeCommission(file);
        com.exe101.entity.CommissionAttachment saved = attachmentRepository.saveAndFlush(
            new com.exe101.entity.CommissionAttachment(commissionId, stored.getFileId(), userId, now()));

        return mapper.toModel(saved, stored);
    }

    // Only the Customer and the commissioned Artist see a Commission; to anyone else it does not exist.
    private com.exe101.entity.Commission requireParticipant(
        Long userId, Optional<com.exe101.entity.Commission> found
    ) {
        com.exe101.entity.Commission entity = found
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_NOT_FOUND));

        if (entity.getCustomerId() == userId) {
            return entity;
        }

        Artist artist = artistService.get(entity.getArtistId());

        if (!artist.getUserId().equals(userId)) {
            throw new AppException(AppErrorMessage.COMMISSION_NOT_FOUND);
        }

        return entity;
    }

    private void requireOpen(com.exe101.entity.Commission entity) {
        if (CommissionStatus.valueOf(entity.getStatus()).isClosed()) {
            throw new AppException(AppErrorMessage.COMMISSION_CLOSED);
        }
    }

    private NewFile toNewFile(FileUpload upload) {
        byte[] bytes = upload.getBytes();

        if (bytes == null || bytes.length == 0) {
            throw new AppException(AppErrorMessage.COMMISSION_INVALID);
        }

        if (bytes.length > maxAttachmentBytes) {
            throw new AppException(AppErrorMessage.COMMISSION_ATTACHMENT_TOO_LARGE);
        }

        String contentType = CommissionFiles.contentType(upload.getOriginalName());

        if (contentType == null) {
            throw new AppException(AppErrorMessage.COMMISSION_ATTACHMENT_TYPE_NOT_SUPPORTED);
        }

        return NewFile.builder()
            .withOriginalName(upload.getOriginalName())
            .withContentType(contentType)
            .withBytes(bytes)
            .build();
    }

    private String requireText(String value) {
        String text = value == null ? "" : value.strip();

        if (text.isEmpty() || text.length() > maxTextLength) {
            throw new AppException(AppErrorMessage.COMMISSION_INVALID);
        }

        return text;
    }

    private Commission assemble(com.exe101.entity.Commission entity, List<CommissionAttachment> attachments) {
        Commission commission = mapper.toModel(entity);
        commission.setAttachments(attachments);

        return commission;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
