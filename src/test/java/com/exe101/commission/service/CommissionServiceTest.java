package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.commission.mapper.CommissionEntityMapper;
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
import com.exe101.entity.Commission;
import com.exe101.entity.CommissionAttachment;
import com.exe101.entity.CommissionEvent;
import com.exe101.entity.CommissionMessage;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.service.FileService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommissionServiceTest {
    private static long customerId = 42L;
    private static long artistUserId = 50L;
    private static long strangerId = 77L;
    private static long artistId = 9L;
    private static long commissionId = 3L;
    private static byte[] fileBytes = "reference".getBytes(StandardCharsets.UTF_8);
    @Mock
    private CommissionRepository repository;
    @Mock
    private CommissionMessageRepository messageRepository;
    @Mock
    private CommissionAttachmentRepository attachmentRepository;
    @Mock
    private CommissionEventRepository eventRepository;
    @Mock
    private ArtistService artistService;
    @Mock
    private CustomerService customerService;
    @Mock
    private FileService fileService;
    @Spy
    private CommissionEntityMapper mapper = Mappers.getMapper(CommissionEntityMapper.class);
    @InjectMocks
    private CommissionServiceImpl service;

    @Test
    void create_request_ok() {
        // given
        givenRequester();
        when(artistService.lockForCommission(artistId)).thenReturn(artist("ACTIVE", true));
        givenCommissionSaved();

        // when
        var result = service.request(customerId, request(" Portrait of my cat ", List.of()));
        var commission = ArgumentCaptor.forClass(Commission.class);
        verify(repository).saveAndFlush(commission.capture());
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getCommissionId()).isEqualTo(commissionId),
            () -> assertThat(result.getStatus()).isEqualTo(CommissionStatus.REQUESTED),
            () -> assertThat(result.getAttachments()).isEmpty(),
            () -> assertThat(commission.getValue().getCustomerId()).isEqualTo(customerId),
            () -> assertThat(commission.getValue().getArtistId()).isEqualTo(artistId),
            () -> assertThat(commission.getValue().getRequestDescription()).isEqualTo("Portrait of my cat"),
            () -> assertThat(commission.getValue().getStatus()).isEqualTo("REQUESTED"),
            () -> assertThat(commission.getValue().getCustomerNameSnapshot()).isEqualTo("Buyer"),
            () -> assertThat(commission.getValue().getArtistNameSnapshot()).isEqualTo("Painter"),
            () -> assertThat(commission.getValue().isAllowPublicDisplay()).isTrue(),
            () -> assertThat(commission.getValue().getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC),
            () -> assertThat(event.getValue().getCommissionId()).isEqualTo(commissionId),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("REQUESTED"),
            () -> assertThat(event.getValue().getFromStatus()).isNull(),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("REQUESTED"),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(customerId),
            () -> assertThat(event.getValue().getActorStaffId()).isNull(),
            () -> verifyNoInteractions(fileService)
        );
    }

    @Test
    void create_request_with_attachments_ok() {
        // given
        givenRequester();
        when(artistService.lockForCommission(artistId)).thenReturn(artist("ACTIVE", true));
        givenCommissionSaved();
        givenFileStored();
        givenAttachmentSaved();

        // when
        var result = service.request(customerId, request("Portrait", List.of(upload("cat.PNG", fileBytes))));
        var file = ArgumentCaptor.forClass(NewFile.class);
        verify(fileService).storeCommission(file.capture());
        var link = ArgumentCaptor.forClass(CommissionAttachment.class);
        verify(attachmentRepository).saveAndFlush(link.capture());

        // then
        assertAll(
            () -> assertThat(file.getValue().getContentType()).isEqualTo("image/png"),
            () -> assertThat(file.getValue().getBytes()).isEqualTo(fileBytes),
            () -> assertThat(link.getValue().getCommissionId()).isEqualTo(commissionId),
            () -> assertThat(link.getValue().getFileId()).isEqualTo(70L),
            () -> assertThat(link.getValue().getUploadedByUserId()).isEqualTo(customerId),
            () -> assertThat(result.getAttachments()).singleElement().satisfies(attachment -> {
                assertThat(attachment.getAttachmentId()).isEqualTo(11L);
                assertThat(attachment.getOriginalName()).isEqualTo("cat.PNG");
                assertThat(attachment.getContentType()).isEqualTo("image/png");
            })
        );
    }

    @Test
    void create_request_artist_not_found_ko() {
        // given
        givenRequester();
        when(artistService.lockForCommission(artistId))
            .thenThrow(new AppException(AppErrorMessage.ARTIST_NOT_FOUND));

        // when / then
        assertThatThrownBy(() -> service.request(customerId, request("Portrait", List.of())))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.ARTIST_NOT_FOUND);
        verifyNothingSaved();
    }

    @Test
    void create_request_artist_deactivated_ko() {
        // given
        givenRequester();
        when(artistService.lockForCommission(artistId)).thenReturn(artist("SUSPENDED", true));

        // when / then
        assertThatThrownBy(() -> service.request(customerId, request("Portrait", List.of())))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.ARTIST_NOT_ACTIVE);
        verifyNothingSaved();
    }

    @Test
    void create_request_artist_not_accepting_ko() {
        // given
        givenRequester();
        when(artistService.lockForCommission(artistId)).thenReturn(artist("ACTIVE", false));

        // when / then
        assertThatThrownBy(() -> service.request(customerId, request("Portrait", List.of())))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.ARTIST_NOT_ACCEPTING_COMMISSIONS);
        verifyNothingSaved();
    }

    @Test
    void create_request_self_commission_ko() {
        // given
        when(customerService.getActive(artistUserId))
            .thenReturn(Customer.builder().withUserId(artistUserId).withDisplayName("Painter").build());
        when(artistService.lockForCommission(artistId)).thenReturn(artist("ACTIVE", true));

        // when / then
        assertThatThrownBy(() -> service.request(artistUserId, request("Portrait", List.of())))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.OWN_ARTIST_PROFILE);
        verifyNothingSaved();
    }

    @Test
    void create_request_bad_attachment_ko() {
        // given
        var wrongType = List.of(upload("model.exe", fileBytes));
        var tooLarge = List.of(upload("big.png", new byte[20 * 1024 * 1024 + 1]));
        var tooMany = Collections.nCopies(11, upload("cat.png", fileBytes));

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> service.request(customerId, request("Portrait", wrongType)))
                .isInstanceOf(AppException.class)
                .extracting("error")
                .isEqualTo(AppErrorMessage.COMMISSION_ATTACHMENT_TYPE_NOT_SUPPORTED),
            () -> assertThatThrownBy(() -> service.request(customerId, request("Portrait", tooLarge)))
                .isInstanceOf(AppException.class)
                .extracting("error")
                .isEqualTo(AppErrorMessage.COMMISSION_ATTACHMENT_TOO_LARGE),
            () -> assertThatThrownBy(() -> service.request(customerId, request("Portrait", tooMany)))
                .isInstanceOf(AppException.class)
                .extracting("error")
                .isEqualTo(AppErrorMessage.COMMISSION_ATTACHMENT_LIMIT_REACHED)
        );
        verifyNoInteractions(artistService);
        verifyNothingSaved();
    }

    @Test
    void send_message_customer_ok() {
        // given
        when(repository.findWithSharedLockByCommissionId(commissionId))
            .thenReturn(Optional.of(commission("REQUESTED")));
        givenMessageSaved();

        // when
        var result = service.sendMessage(customerId, commissionId, " Can it be blue? ");
        var message = ArgumentCaptor.forClass(CommissionMessage.class);
        verify(messageRepository).saveAndFlush(message.capture());

        // then
        assertAll(
            () -> assertThat(result.getMessageId()).isEqualTo(21L),
            () -> assertThat(message.getValue().getCommissionId()).isEqualTo(commissionId),
            () -> assertThat(message.getValue().getSenderUserId()).isEqualTo(customerId),
            () -> assertThat(message.getValue().getBody()).isEqualTo("Can it be blue?"),
            () -> assertThat(message.getValue().getSentAt().getOffset()).isEqualTo(ZoneOffset.UTC)
        );
    }

    @Test
    void send_message_artist_ok() {
        // given
        when(repository.findWithSharedLockByCommissionId(commissionId))
            .thenReturn(Optional.of(commission("REQUESTED")));
        when(artistService.get(artistId)).thenReturn(artist("ACTIVE", true));
        givenMessageSaved();

        // when
        var result = service.sendMessage(artistUserId, commissionId, "Yes, any shade?");

        // then
        assertAll(
            () -> assertThat(result.getSenderUserId()).isEqualTo(artistUserId),
            () -> assertThat(result.getBody()).isEqualTo("Yes, any shade?")
        );
    }

    @Test
    void message_while_cancellation_pending_ok() {
        // given
        // Messages stay possible while a formal cancellation request awaits its answer (§24.7).
        when(repository.findWithSharedLockByCommissionId(commissionId))
            .thenReturn(Optional.of(commission("IN_PROGRESS")));
        givenMessageSaved();

        // when
        var result = service.sendMessage(customerId, commissionId, "Can we talk about the cancellation?");

        // then
        assertAll(
            () -> assertThat(result.getMessageId()).isEqualTo(21L),
            () -> assertThat(result.getBody()).isEqualTo("Can we talk about the cancellation?")
        );
    }

    @Test
    void send_message_non_participant_ko() {
        // given
        when(repository.findWithSharedLockByCommissionId(commissionId))
            .thenReturn(Optional.of(commission("REQUESTED")));
        when(artistService.get(artistId)).thenReturn(artist("ACTIVE", true));

        // when / then
        assertThatThrownBy(() -> service.sendMessage(strangerId, commissionId, "Hello"))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.COMMISSION_NOT_FOUND);
        verify(messageRepository, never()).saveAndFlush(any());
    }

    @Test
    void send_message_closed_commission_ko() {
        for (var status : List.of("CANCELLED", "DECLINED", "COMPLETED")) {
            // given
            when(repository.findWithSharedLockByCommissionId(commissionId))
                .thenReturn(Optional.of(commission(status)));

            // when / then
            assertThatThrownBy(() -> service.sendMessage(customerId, commissionId, "Hello"))
                .isInstanceOf(AppException.class)
                .extracting("error")
                .isEqualTo(AppErrorMessage.COMMISSION_CLOSED);
        }

        verify(messageRepository, never()).saveAndFlush(any());
    }

    @Test
    void send_message_artist_deactivated_ok() {
        // given
        when(repository.findWithSharedLockByCommissionId(commissionId))
            .thenReturn(Optional.of(commission("REQUESTED")));
        when(artistService.get(artistId)).thenReturn(artist("SUSPENDED", true));
        givenMessageSaved();

        // when
        var result = service.sendMessage(artistUserId, commissionId, "Still on it");

        // then
        assertThat(result.getSenderUserId()).isEqualTo(artistUserId);
    }

    @Test
    void list_messages_non_participant_ko() {
        // given
        when(repository.findById(commissionId)).thenReturn(Optional.of(commission("REQUESTED")));
        when(artistService.get(artistId)).thenReturn(artist("ACTIVE", true));

        // when / then
        assertThatThrownBy(() -> service.listMessages(strangerId, commissionId))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.COMMISSION_NOT_FOUND);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void get_commission_not_found_ko() {
        // given
        when(repository.findById(commissionId)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.get(customerId, commissionId))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.COMMISSION_NOT_FOUND);
    }

    @Test
    void add_attachment_participant_ok() {
        // given
        when(repository.findWithLockByCommissionId(commissionId)).thenReturn(Optional.of(commission("REQUESTED")));
        when(artistService.get(artistId)).thenReturn(artist("ACTIVE", true));
        when(attachmentRepository.countByCommissionId(commissionId)).thenReturn(9L);
        givenFileStored();
        givenAttachmentSaved();

        // when
        var result = service.addAttachment(artistUserId, commissionId, upload("sketch.pdf", fileBytes));
        var link = ArgumentCaptor.forClass(CommissionAttachment.class);
        verify(attachmentRepository).saveAndFlush(link.capture());

        // then
        assertAll(
            () -> assertThat(result.getAttachmentId()).isEqualTo(11L),
            () -> assertThat(link.getValue().getCommissionId()).isEqualTo(commissionId),
            () -> assertThat(link.getValue().getUploadedByUserId()).isEqualTo(artistUserId)
        );
    }

    @Test
    void add_attachment_non_participant_ko() {
        // given
        when(repository.findWithLockByCommissionId(commissionId)).thenReturn(Optional.of(commission("REQUESTED")));
        when(artistService.get(artistId)).thenReturn(artist("ACTIVE", true));

        // when / then
        assertThatThrownBy(() -> service.addAttachment(strangerId, commissionId, upload("cat.png", fileBytes)))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.COMMISSION_NOT_FOUND);
        verifyNoInteractions(fileService);
        verify(attachmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void read_attachment_non_participant_ko() {
        // given
        when(repository.findById(commissionId)).thenReturn(Optional.of(commission("REQUESTED")));
        when(artistService.get(artistId)).thenReturn(artist("ACTIVE", true));

        // when / then
        assertThatThrownBy(() -> service.readAttachment(strangerId, commissionId, 11L))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.COMMISSION_NOT_FOUND);
        verifyNoInteractions(fileService);
    }

    private void givenRequester() {
        when(customerService.getActive(customerId))
            .thenReturn(Customer.builder().withUserId(customerId).withDisplayName("Buyer").build());
    }

    private void givenCommissionSaved() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            Commission entity = invocation.getArgument(0);
            entity.setCommissionId(commissionId);
            return entity;
        });
    }

    private void givenFileStored() {
        when(fileService.storeCommission(any())).thenAnswer(invocation -> {
            NewFile file = invocation.getArgument(0);
            return StoredFileInfo.builder()
                .withFileId(70L)
                .withOriginalName(file.getOriginalName())
                .withContentType(file.getContentType())
                .withByteLength(file.getBytes().length)
                .withAccessClass("COMMISSION")
                .build();
        });
    }

    private void givenAttachmentSaved() {
        when(attachmentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CommissionAttachment entity = invocation.getArgument(0);
            entity.setAttachmentId(11L);
            return entity;
        });
    }

    private void givenMessageSaved() {
        when(messageRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CommissionMessage entity = invocation.getArgument(0);
            entity.setMessageId(21L);
            return entity;
        });
    }

    private void verifyNothingSaved() {
        verify(repository, never()).saveAndFlush(any());
        verify(attachmentRepository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
        verifyNoInteractions(fileService);
    }

    private CommissionRequest request(String description, List<FileUpload> uploads) {
        return CommissionRequest.builder()
            .withArtistId(artistId)
            .withDescription(description)
            .withAllowPublicDisplay(true)
            .withUploads(uploads)
            .build();
    }

    private FileUpload upload(String name, byte[] bytes) {
        return FileUpload.builder().withOriginalName(name).withBytes(bytes).build();
    }

    private Artist artist(String status, boolean accepting) {
        return Artist.builder()
            .withArtistId(artistId)
            .withUserId(artistUserId)
            .withDisplayName("Painter")
            .withStatus(status)
            .withAcceptingCommissions(accepting)
            .build();
    }

    private Commission commission(String status) {
        var entity = new Commission(customerId, artistId, "Portrait", status, "Buyer", "Painter",
            OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC), null, null);
        entity.setCommissionId(commissionId);

        return entity;
    }
}
