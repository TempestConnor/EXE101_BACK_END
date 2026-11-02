package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.commission.mapper.CommissionDeliverableEntityMapper;
import com.exe101.commission.mapper.CommissionEntityMapper;
import com.exe101.commission.model.CommissionStatus;
import com.exe101.commission.model.DeliverableSubmission;
import com.exe101.commission.model.FileUpload;
import com.exe101.commission.repository.CancellationRequestRepository;
import com.exe101.commission.repository.CommissionDeliverableRepository;
import com.exe101.commission.repository.CommissionEventRepository;
import com.exe101.commission.repository.CommissionQuoteRepository;
import com.exe101.commission.repository.CommissionRepository;
import com.exe101.commission.repository.DeliverableFileRepository;
import com.exe101.commission.repository.RevisionRequestRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.Commission;
import com.exe101.entity.CommissionDeliverable;
import com.exe101.entity.CommissionEvent;
import com.exe101.entity.CommissionQuote;
import com.exe101.entity.DeliverableFile;
import com.exe101.entity.DeliverableFileId;
import com.exe101.entity.RevisionRequest;
import com.exe101.file.model.FileContent;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.service.FileService;
import com.exe101.proceeds.service.ProceedsService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ByteArrayResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommissionDeliveryServiceTest {
    private static long customerId = 42L;
    private static long artistUserId = 50L;
    private static long strangerId = 77L;
    private static long artistId = 9L;
    private static long commissionId = 3L;
    private static long deliverableId = 31L;
    private static long revisionId = 61L;
    private static long fileId = 70L;
    private static long quoteId = 21L;
    private static byte[] fileBytes = {1, 2, 3};
    @Mock
    private CommissionRepository repository;
    @Mock
    private CommissionDeliverableRepository deliverableRepository;
    @Mock
    private DeliverableFileRepository deliverableFileRepository;
    @Mock
    private RevisionRequestRepository revisionRepository;
    @Mock
    private CommissionEventRepository eventRepository;
    @Mock
    private ArtistService artistService;
    @Mock
    private FileService fileService;
    @Mock
    private CommissionQuoteRepository quoteRepository;
    @Mock
    private ProceedsService proceedsService;
    @Mock
    private CancellationRequestRepository cancellationRepository;
    @Spy
    private CommissionEntityMapper commissionMapper = Mappers.getMapper(CommissionEntityMapper.class);
    @Spy
    private CommissionDeliverableEntityMapper mapper = Mappers.getMapper(CommissionDeliverableEntityMapper.class);
    @InjectMocks
    private CommissionDeliveryServiceImpl service;

    @BeforeEach
    void setUp() {
        when(artistService.get(artistId)).thenReturn(artist("ACTIVE"));
        when(deliverableRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CommissionDeliverable deliverable = invocation.getArgument(0);
            deliverable.setDeliverableId(deliverableId);
            return deliverable;
        });
        when(revisionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            RevisionRequest revision = invocation.getArgument(0);
            revision.setRevisionId(revisionId);
            return revision;
        });
        when(fileService.storeCommission(any())).thenAnswer(invocation -> {
            NewFile file = invocation.getArgument(0);
            return StoredFileInfo.builder()
                .withFileId(fileId)
                .withOriginalName(file.getOriginalName())
                .withContentType(file.getContentType())
                .withByteLength(file.getBytes().length)
                .withAccessClass("COMMISSION")
                .build();
        });
        givenQuote("100001.0000", "10001.0000");
    }

    @Test
    void submit_first_version_ok() {
        // given
        var commission = givenCommission("IN_PROGRESS");
        when(deliverableRepository.findLastVersionNo(commissionId)).thenReturn(0);

        // when
        var result = service.submit(artistUserId, commissionId, submission(" Final render ", upload("cat.png")));
        var saved = ArgumentCaptor.forClass(CommissionDeliverable.class);
        verify(deliverableRepository).saveAndFlush(saved.capture());
        var stored = ArgumentCaptor.forClass(NewFile.class);
        verify(fileService).storeCommission(stored.capture());
        var link = ArgumentCaptor.forClass(DeliverableFile.class);
        verify(deliverableFileRepository).saveAndFlush(link.capture());
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getDeliverableId()).isEqualTo(deliverableId),
            () -> assertThat(result.getVersionNo()).isEqualTo(1),
            () -> assertThat(result.getNotes()).isEqualTo("Final render"),
            () -> assertThat(result.getFiles()).extracting("fileId").containsExactly(fileId),
            () -> assertThat(result.getRevisionRequests()).isEmpty(),
            () -> assertThat(saved.getValue().getCommissionId()).isEqualTo(commissionId),
            () -> assertThat(saved.getValue().getSubmittedAt().getOffset()).isEqualTo(ZoneOffset.UTC),
            () -> assertThat(stored.getValue().getContentType()).isEqualTo("image/png"),
            () -> assertThat(link.getValue().getId()).isEqualTo(new DeliverableFileId(deliverableId, fileId)),
            () -> assertThat(commission.getStatus()).isEqualTo("SUBMITTED_FOR_REVIEW"),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("DELIVERABLE_SUBMITTED"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("IN_PROGRESS"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("SUBMITTED_FOR_REVIEW"),
            () -> assertThat(event.getValue().getRelatedId()).isEqualTo(deliverableId),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(artistUserId)
        );
    }

    @Test
    void submit_without_files_ko() {
        // given
        givenCommission("IN_PROGRESS");

        // when / then
        assertRejected(() -> service.submit(artistUserId, commissionId, submission("Final render")),
            AppErrorMessage.COMMISSION_INVALID);
        verify(repository, never()).findWithLockByCommissionId(anyLong());
    }

    @Test
    void submit_by_customer_ko() {
        // given
        givenCommission("IN_PROGRESS");

        // when / then
        assertRejected(() -> service.submit(customerId, commissionId, submission(null, upload("cat.png"))),
            AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void submit_while_under_review_ko() {
        // given
        givenCommission("SUBMITTED_FOR_REVIEW");

        // when / then
        assertRejected(() -> service.submit(artistUserId, commissionId, submission(null, upload("cat.png"))),
            AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
    }

    @Test
    void pending_cancellation_blocks_submission_ko() {
        // given
        givenCommission("IN_PROGRESS");
        when(cancellationRepository.existsByCommissionIdAndStatus(commissionId, "PENDING")).thenReturn(true);

        // when / then
        assertRejected(() -> service.submit(artistUserId, commissionId, submission(null, upload("cat.png"))),
            AppErrorMessage.CANCELLATION_PENDING);
    }

    @Test
    void disputed_commission_refuses_submission_ko() {
        // given
        givenCommission("DISPUTED");

        // when / then
        assertRejected(() -> service.submit(artistUserId, commissionId, submission(null, upload("cat.png"))),
            AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
    }

    @Test
    void submit_after_revision_request_ok() {
        // given
        var commission = givenCommission("REVISION_REQUESTED");
        when(deliverableRepository.findLastVersionNo(commissionId)).thenReturn(1);

        // when
        var result = service.submit(artistUserId, commissionId, submission(null, upload("cat-v2.png")));
        var saved = ArgumentCaptor.forClass(CommissionDeliverable.class);
        verify(deliverableRepository).saveAndFlush(saved.capture());
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        // Version 1 is only read for its number; nothing changes or removes it.
        assertAll(
            () -> assertThat(result.getVersionNo()).isEqualTo(2),
            () -> assertThat(result.getNotes()).isNull(),
            () -> assertThat(saved.getValue().getVersionNo()).isEqualTo(2),
            () -> assertThat(commission.getStatus()).isEqualTo("SUBMITTED_FOR_REVIEW"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("REVISION_REQUESTED")
        );
        verify(deliverableRepository, never()).delete(any());
    }

    @Test
    void submit_by_deactivated_artist_ok() {
        // given
        var commission = givenCommission("IN_PROGRESS");
        when(artistService.get(artistId)).thenReturn(artist("SUSPENDED"));
        when(deliverableRepository.findLastVersionNo(commissionId)).thenReturn(0);

        // when
        var result = service.submit(artistUserId, commissionId, submission(null, upload("cat.png")));

        // then
        assertAll(
            () -> assertThat(result.getVersionNo()).isEqualTo(1),
            () -> assertThat(commission.getStatus()).isEqualTo("SUBMITTED_FOR_REVIEW")
        );
    }

    @Test
    void submit_unknown_commission_ko() {
        // given
        when(repository.findWithLockByCommissionId(commissionId)).thenReturn(Optional.empty());

        // when / then
        assertRejected(() -> service.submit(artistUserId, commissionId, submission(null, upload("cat.png"))),
            AppErrorMessage.COMMISSION_NOT_FOUND);
    }

    @Test
    void approve_latest_version_ok() {
        // given
        var commission = givenCommission("SUBMITTED_FOR_REVIEW");
        givenDeliverable(1, 1);

        // when
        var result = service.approve(customerId, commissionId, deliverableId);
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        // The final 50% is due now and is paid through a payment attempt, so no Payment is created here.
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(CommissionStatus.AWAITING_PAYMENT),
            () -> assertThat(commission.getStatus()).isEqualTo("AWAITING_PAYMENT"),
            () -> assertThat(commission.getCompletedAt()).isNull(),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("DELIVERABLE_APPROVED"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("SUBMITTED_FOR_REVIEW"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("AWAITING_PAYMENT"),
            () -> assertThat(event.getValue().getRelatedId()).isEqualTo(deliverableId),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(customerId)
        );
        verify(proceedsService, never()).releaseQuote(any(), any());
    }

    @Test
    void approve_zero_final_completes_ok() {
        // given
        // Price 1 and tax 0: the first installment is 1 VND and the final one 0 VND, which cannot be paid.
        var commission = givenCommission("SUBMITTED_FOR_REVIEW");
        givenDeliverable(1, 1);
        var quote = givenQuote("1.0000", "0.0000");

        // when
        var result = service.approve(customerId, commissionId, deliverableId);
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(CommissionStatus.COMPLETED),
            () -> assertThat(commission.getStatus()).isEqualTo("COMPLETED"),
            () -> assertThat(commission.getCompletedAt()).isNotNull(),
            () -> assertThat(quote.getStatus()).isEqualTo("PAID"),
            () -> assertThat(quote.getPaidAt()).isEqualTo(commission.getCompletedAt()),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("DELIVERABLE_APPROVED"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("SUBMITTED_FOR_REVIEW"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("COMPLETED")
        );
        verify(quoteRepository).saveAndFlush(quote);
        verify(proceedsService).releaseQuote(eq(quoteId), eq(commission.getCompletedAt()));
    }

    @Test
    void approve_stale_version_ko() {
        // given
        givenCommission("SUBMITTED_FOR_REVIEW");
        givenDeliverable(1, 2);

        // when / then
        assertRejected(() -> service.approve(customerId, commissionId, deliverableId),
            AppErrorMessage.DELIVERABLE_NOT_CURRENT);
    }

    @Test
    void pending_cancellation_blocks_approval_ko() {
        // given
        givenCommission("SUBMITTED_FOR_REVIEW");
        givenDeliverable(1, 1);
        when(cancellationRepository.existsByCommissionIdAndStatus(commissionId, "PENDING")).thenReturn(true);

        // when / then
        assertRejected(() -> service.approve(customerId, commissionId, deliverableId),
            AppErrorMessage.CANCELLATION_PENDING);
        verify(proceedsService, never()).releaseQuote(anyLong(), any());
    }

    @Test
    void approve_twice_ko() {
        // given
        givenCommission("AWAITING_PAYMENT");
        givenDeliverable(1, 1);

        // when / then
        assertRejected(() -> service.approve(customerId, commissionId, deliverableId),
            AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
    }

    @Test
    void approve_by_artist_ko() {
        // given
        givenCommission("SUBMITTED_FOR_REVIEW");
        givenDeliverable(1, 1);

        // when / then
        assertRejected(() -> service.approve(artistUserId, commissionId, deliverableId),
            AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void approve_unknown_deliverable_ko() {
        // given
        givenCommission("SUBMITTED_FOR_REVIEW");
        when(deliverableRepository.findByDeliverableIdAndCommissionId(deliverableId, commissionId))
            .thenReturn(Optional.empty());

        // when / then
        assertRejected(() -> service.approve(customerId, commissionId, deliverableId),
            AppErrorMessage.DELIVERABLE_NOT_FOUND);
    }

    @Test
    void request_revision_ok() {
        // given
        var commission = givenCommission("SUBMITTED_FOR_REVIEW");
        givenDeliverable(1, 1);

        // when
        var result = service.requestRevision(customerId, commissionId, deliverableId, " Make the cat orange ");
        var saved = ArgumentCaptor.forClass(RevisionRequest.class);
        verify(revisionRepository).saveAndFlush(saved.capture());
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getRevisionId()).isEqualTo(revisionId),
            () -> assertThat(result.getDeliverableId()).isEqualTo(deliverableId),
            () -> assertThat(saved.getValue().getCommissionId()).isEqualTo(commissionId),
            () -> assertThat(saved.getValue().getRequestedByUserId()).isEqualTo(customerId),
            () -> assertThat(saved.getValue().getDetails()).isEqualTo("Make the cat orange"),
            () -> assertThat(commission.getStatus()).isEqualTo("REVISION_REQUESTED"),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("REVISION_REQUESTED"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("SUBMITTED_FOR_REVIEW"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("REVISION_REQUESTED"),
            () -> assertThat(event.getValue().getRelatedId()).isEqualTo(revisionId)
        );
        verify(deliverableRepository, never()).saveAndFlush(any());
    }

    @Test
    void request_revision_without_details_ko() {
        // given
        givenCommission("SUBMITTED_FOR_REVIEW");
        givenDeliverable(1, 1);

        // when / then
        assertRejected(() -> service.requestRevision(customerId, commissionId, deliverableId, "   "),
            AppErrorMessage.COMMISSION_INVALID);
    }

    @Test
    void request_revision_outside_review_ko() {
        // given
        givenCommission("IN_PROGRESS");
        givenDeliverable(1, 1);

        // when / then
        assertRejected(() -> service.requestRevision(customerId, commissionId, deliverableId, "Make it orange"),
            AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
    }

    @Test
    void read_file_by_artist_ok() {
        // given
        givenReadableFile();
        var content = FileContent.builder().withFileId(fileId).withContentType("image/png")
            .withByteLength(fileBytes.length).withContent(new ByteArrayResource(fileBytes)).build();
        when(fileService.read(fileId)).thenReturn(content);

        // when
        var result = service.readFile(artistUserId, commissionId, deliverableId, fileId);

        // then
        assertThat(result).isSameAs(content);
    }

    @Test
    void read_file_by_customer_ko() {
        // given
        givenReadableFile();

        // when / then
        assertThatThrownBy(() -> service.readFile(customerId, commissionId, deliverableId, fileId))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.DELIVERABLE_LOCKED);
        verify(fileService, never()).read(any());
    }

    @Test
    void read_file_customer_after_completion_ok() {
        // given
        givenReadableFile("COMPLETED", 2, 2);
        var content = FileContent.builder().withFileId(fileId).withContentType("image/png")
            .withByteLength(fileBytes.length).withContent(new ByteArrayResource(fileBytes)).build();
        when(fileService.read(fileId)).thenReturn(content);

        // when
        var result = service.readFile(customerId, commissionId, deliverableId, fileId);

        // then
        assertThat(result).isSameAs(content);
    }

    @Test
    void read_file_customer_earlier_version_ko() {
        // given
        givenReadableFile("COMPLETED", 1, 2);

        // when / then
        assertThatThrownBy(() -> service.readFile(customerId, commissionId, deliverableId, fileId))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.DELIVERABLE_LOCKED);
        verify(fileService, never()).read(any());
    }

    @Test
    void read_file_customer_awaiting_final_payment_ko() {
        // given
        givenReadableFile("AWAITING_PAYMENT", 1, 1);

        // when / then
        assertThatThrownBy(() -> service.readFile(customerId, commissionId, deliverableId, fileId))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.DELIVERABLE_LOCKED);
        verify(fileService, never()).read(any());
    }

    @Test
    void list_versions_by_outsider_ko() {
        // given
        when(repository.findById(commissionId)).thenReturn(Optional.of(commission("SUBMITTED_FOR_REVIEW")));

        // when / then
        assertThatThrownBy(() -> service.list(strangerId, commissionId))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.COMMISSION_NOT_FOUND);
        verify(deliverableRepository, never()).findByCommissionIdOrderByVersionNoAsc(anyLong());
    }

    // Every rejection happens before anything is written.
    private void assertRejected(Runnable action, AppErrorMessage error) {
        assertThatThrownBy(action::run)
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(error);
        verify(deliverableRepository, never()).saveAndFlush(any());
        verify(deliverableFileRepository, never()).saveAndFlush(any());
        verify(revisionRepository, never()).saveAndFlush(any());
        verify(fileService, never()).storeCommission(any());
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    private Commission givenCommission(String status) {
        var commission = commission(status);
        when(repository.findWithLockByCommissionId(commissionId)).thenReturn(Optional.of(commission));
        when(repository.saveAndFlush(commission)).thenReturn(commission);
        return commission;
    }

    private void givenDeliverable(int versionNo, int lastVersionNo) {
        var deliverable = new CommissionDeliverable(commissionId, versionNo, null, now());
        deliverable.setDeliverableId(deliverableId);
        when(deliverableRepository.findByDeliverableIdAndCommissionId(deliverableId, commissionId))
            .thenReturn(Optional.of(deliverable));
        when(deliverableRepository.findLastVersionNo(commissionId)).thenReturn(lastVersionNo);
    }

    private void givenReadableFile() {
        givenReadableFile("SUBMITTED_FOR_REVIEW", 1, 1);
    }

    private void givenReadableFile(String status, int versionNo, int lastVersionNo) {
        when(repository.findById(commissionId)).thenReturn(Optional.of(commission(status)));
        givenDeliverable(versionNo, lastVersionNo);
        when(deliverableFileRepository.existsById(new DeliverableFileId(deliverableId, fileId))).thenReturn(true);
    }

    // The Commission's one accepted Quote, paid in two installments.
    private CommissionQuote givenQuote(String price, String tax) {
        var quote = new CommissionQuote(commissionId, 1, "Full-body portrait", "3 weeks", new BigDecimal(price),
            new BigDecimal(tax), "VND", "ACCEPTED", now());
        quote.setQuoteId(quoteId);
        quote.setAcceptedAt(now());
        when(quoteRepository.findByCommissionIdAndStatus(commissionId, "ACCEPTED")).thenReturn(List.of(quote));
        return quote;
    }

    private Commission commission(String status) {
        var commission = new Commission(customerId, artistId, "Portrait of my cat", status, "Buyer", "Painter",
            now(), null, null);
        commission.setCommissionId(commissionId);
        return commission;
    }

    private DeliverableSubmission submission(String notes, FileUpload... uploads) {
        return DeliverableSubmission.builder()
            .withNotes(notes)
            .withUploads(List.of(uploads))
            .build();
    }

    private FileUpload upload(String name) {
        return FileUpload.builder().withOriginalName(name).withBytes(fileBytes).build();
    }

    private Artist artist(String status) {
        return Artist.builder()
            .withArtistId(artistId)
            .withUserId(artistUserId)
            .withDisplayName("Painter")
            .withStatus(status)
            .withAcceptingCommissions(true)
            .build();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
