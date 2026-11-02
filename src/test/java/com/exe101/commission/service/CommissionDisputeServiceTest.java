package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.commission.mapper.CommissionCancellationEntityMapper;
import com.exe101.commission.mapper.CommissionEntityMapper;
import com.exe101.commission.mapper.CommissionQuoteEntityMapper;
import com.exe101.commission.model.DisputeOutcome;
import com.exe101.commission.model.DisputeResolution;
import com.exe101.commission.model.DisputeStatus;
import com.exe101.commission.repository.CancellationRequestRepository;
import com.exe101.commission.repository.CommissionDisputeRepository;
import com.exe101.commission.repository.CommissionEventRepository;
import com.exe101.commission.repository.CommissionMessageRepository;
import com.exe101.commission.repository.CommissionQuoteRepository;
import com.exe101.commission.repository.CommissionRepository;
import com.exe101.commission.repository.QuotePaymentRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.Commission;
import com.exe101.entity.CommissionDispute;
import com.exe101.entity.CommissionEvent;
import com.exe101.entity.CommissionQuote;
import com.exe101.entity.Payment;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommissionDisputeServiceTest {
    private static long customerId = 42L;
    private static long artistUserId = 50L;
    private static long strangerId = 77L;
    private static long artistId = 9L;
    private static long commissionId = 3L;
    private static long quoteId = 21L;
    private static long disputeId = 91L;
    private static long staffId = 5L;
    @Mock
    private CommissionRepository repository;
    @Mock
    private CommissionDisputeRepository disputeRepository;
    @Mock
    private CancellationRequestRepository cancellationRepository;
    @Mock
    private CommissionMessageRepository messageRepository;
    @Mock
    private CommissionQuoteRepository quoteRepository;
    @Mock
    private QuotePaymentRepository paymentRepository;
    @Mock
    private CommissionEventRepository eventRepository;
    @Mock
    private CommissionDeliveryService deliveryService;
    @Mock
    private ArtistService artistService;
    @Mock
    private AuditService auditService;
    @Mock
    private CommissionRefundService refundService;
    private CommissionDisputeServiceImpl service;

    @BeforeEach
    void setUp() {
        var cancellations = new CommissionCancellations(repository, quoteRepository, paymentRepository,
            eventRepository);
        service = new CommissionDisputeServiceImpl(repository, disputeRepository, cancellationRepository,
            messageRepository, quoteRepository, eventRepository, Mappers.getMapper(CommissionEntityMapper.class),
            Mappers.getMapper(CommissionQuoteEntityMapper.class),
            Mappers.getMapper(CommissionCancellationEntityMapper.class), deliveryService, artistService, auditService,
            cancellations, refundService);
        when(artistService.get(artistId)).thenReturn(artist());
        when(disputeRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CommissionDispute dispute = invocation.getArgument(0);

            if (dispute.getDisputeId() == null) {
                dispute.setDisputeId(disputeId);
            }

            return dispute;
        });
        when(quoteRepository.findByCommissionIdAndStatus(commissionId, "OFFERED")).thenReturn(List.of());
        when(quoteRepository.findByCommissionIdAndStatus(commissionId, "ACCEPTED")).thenReturn(List.of());
    }

    @Test
    void open_dispute_ok() {
        // given
        var commission = givenCommission("IN_PROGRESS");

        // when
        var result = service.open(artistUserId, commissionId, " No reply for two weeks ");
        var saved = ArgumentCaptor.forClass(CommissionDispute.class);
        verify(disputeRepository).saveAndFlush(saved.capture());
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getDisputeId()).isEqualTo(disputeId),
            () -> assertThat(result.getStatus()).isEqualTo(DisputeStatus.OPEN),
            () -> assertThat(saved.getValue().getOpenedByUserId()).isEqualTo(artistUserId),
            () -> assertThat(saved.getValue().getReason()).isEqualTo("No reply for two weeks"),
            () -> assertThat(saved.getValue().getOutcome()).isNull(),
            () -> assertThat(commission.getStatus()).isEqualTo("DISPUTED"),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("DISPUTE_OPENED"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("IN_PROGRESS"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("DISPUTED"),
            () -> assertThat(event.getValue().getRelatedId()).isEqualTo(disputeId),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(artistUserId)
        );
    }

    @Test
    void open_dispute_already_active_ko() {
        // given
        givenCommission("DISPUTED");

        // when / then
        assertRejected(() -> service.open(customerId, commissionId, "Second dispute"),
            AppErrorMessage.DISPUTE_ALREADY_OPEN);
    }

    @Test
    void open_dispute_closed_commission_ko() {
        // given
        givenCommission("CANCELLED");

        // when / then
        assertRejected(() -> service.open(customerId, commissionId, "Too late"), AppErrorMessage.COMMISSION_CLOSED);
    }

    @Test
    void open_dispute_while_cancellation_pending_ko() {
        // given
        givenCommission("IN_PROGRESS");
        when(cancellationRepository.existsByCommissionIdAndStatus(commissionId, "PENDING")).thenReturn(true);

        // when / then
        assertRejected(() -> service.open(customerId, commissionId, "The Artist will not answer"),
            AppErrorMessage.CANCELLATION_PENDING);
    }

    @Test
    void open_dispute_blank_reason_ko() {
        // given
        givenCommission("IN_PROGRESS");

        // when / then
        assertRejected(() -> service.open(customerId, commissionId, " "), AppErrorMessage.COMMISSION_INVALID);
        verify(repository, never()).findWithLockByCommissionId(anyLong());
    }

    @Test
    void open_dispute_non_participant_ko() {
        // given
        givenCommission("IN_PROGRESS");

        // when / then
        assertRejected(() -> service.open(strangerId, commissionId, "Not mine"), AppErrorMessage.COMMISSION_NOT_FOUND);
    }

    @Test
    void review_dispute_ok() {
        // given
        var commission = givenCommission("DISPUTED");
        var dispute = givenDispute("OPEN");

        // when
        var result = service.review(staffId, disputeId);
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(audit.capture());
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(DisputeStatus.UNDER_REVIEW),
            () -> assertThat(dispute.getStatus()).isEqualTo("UNDER_REVIEW"),
            () -> assertThat(commission.getStatus()).isEqualTo("DISPUTED"),
            () -> assertThat(audit.getValue().getStaffId()).isEqualTo(staffId),
            () -> assertThat(audit.getValue().getActionType()).isEqualTo("COMMISSION_DISPUTE_REVIEW_STARTED"),
            () -> assertThat(audit.getValue().getTargetType()).isEqualTo("COMMISSION_DISPUTE"),
            () -> assertThat(audit.getValue().getTargetId()).isEqualTo(disputeId),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("DISPUTE_UNDER_REVIEW"),
            () -> assertThat(event.getValue().getActorStaffId()).isEqualTo(staffId),
            () -> assertThat(event.getValue().getActorUserId()).isNull()
        );
    }

    @Test
    void review_dispute_not_open_ko() {
        // given
        givenCommission("DISPUTED");
        givenDispute("UNDER_REVIEW");

        // when / then
        assertRejected(() -> service.review(staffId, disputeId), AppErrorMessage.DISPUTE_ACTION_NOT_ALLOWED);
    }

    @Test
    void resolve_continue_restores_status_ok() {
        // given
        var commission = givenCommission("DISPUTED");
        var dispute = givenDispute("UNDER_REVIEW");
        givenOpenedFrom("SUBMITTED_FOR_REVIEW");

        // when
        var result = service.resolve(staffId, disputeId, resolution(DisputeOutcome.CONTINUE, " Keep working "));
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(audit.capture());
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(DisputeStatus.RESOLVED),
            () -> assertThat(result.getOutcome()).isEqualTo(DisputeOutcome.CONTINUE),
            () -> assertThat(result.getDecidedByStaffId()).isEqualTo(staffId),
            () -> assertThat(result.getResolutionReason()).isEqualTo("Keep working"),
            () -> assertThat(dispute.getResolvedAt()).isNotNull(),
            () -> assertThat(commission.getStatus()).isEqualTo("SUBMITTED_FOR_REVIEW"),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("DISPUTE_RESOLVED"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("DISPUTED"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("SUBMITTED_FOR_REVIEW"),
            () -> assertThat(event.getValue().getActorStaffId()).isEqualTo(staffId),
            () -> assertThat(audit.getValue().getActionType()).isEqualTo("COMMISSION_DISPUTE_RESOLVED"),
            () -> assertThat(audit.getValue().getReason()).isEqualTo("Keep working"),
            () -> assertThat(audit.getValue().getDetails())
                .isEqualTo("commissionId=3;outcome=CONTINUE;from=DISPUTED;to=SUBMITTED_FOR_REVIEW")
        );
    }

    @Test
    void resolve_cancel_refund_ok() {
        // given
        var commission = givenCommission("DISPUTED");
        givenDispute("UNDER_REVIEW");
        givenAcceptedQuote();
        var first = payment(1, "SUCCEEDED");
        var last = payment(2, "PENDING");
        when(paymentRepository.findByQuoteIdAndStatus(quoteId, "PENDING")).thenReturn(List.of(last));

        // when
        var result = service.resolve(staffId, disputeId, resolution(DisputeOutcome.CANCEL_REFUND, "Artist left"));
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(audit.capture());
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getOutcome()).isEqualTo(DisputeOutcome.CANCEL_REFUND),
            () -> assertThat(commission.getStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(last.getStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(first.getStatus()).isEqualTo("SUCCEEDED"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(event.getValue().getActorStaffId()).isEqualTo(staffId),
            () -> assertThat(audit.getValue().getDetails())
                .isEqualTo("commissionId=3;outcome=CANCEL_REFUND;from=DISPUTED;to=CANCELLED")
        );
        // The paid installment stays HELD while the Commission's refund is opened for it (F20).
        verify(paymentRepository, never()).saveAndFlush(first);
        verify(refundService).openOnCancellation(eq(commissionId), isNull(), eq(staffId), eq("Artist left"), any());
        verify(refundService, never()).releaseOnCancellation(anyLong(), any());
    }

    @Test
    void resolve_cancel_no_refund_ok() {
        // given
        var commission = givenCommission("DISPUTED");
        givenDispute("UNDER_REVIEW");

        // when
        var result = service.resolve(staffId, disputeId,
            resolution(DisputeOutcome.CANCEL_NO_REFUND, "Customer abandoned the work"));
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(audit.capture());

        // then
        assertAll(
            () -> assertThat(result.getOutcome()).isEqualTo(DisputeOutcome.CANCEL_NO_REFUND),
            () -> assertThat(commission.getStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(audit.getValue().getReason()).isEqualTo("Customer abandoned the work")
        );
        verify(paymentRepository, never()).saveAndFlush(any());
        // No refund: the held installment goes to the Artist (F20).
        verify(refundService).releaseOnCancellation(eq(commissionId), any());
        verify(refundService, never()).openOnCancellation(anyLong(), any(), any(), any(), any());
    }

    @Test
    void resolve_not_under_review_ko() {
        // given
        givenCommission("DISPUTED");
        givenDispute("OPEN");

        // when / then
        assertRejected(() -> service.resolve(staffId, disputeId, resolution(DisputeOutcome.CONTINUE, "Keep working")),
            AppErrorMessage.DISPUTE_ACTION_NOT_ALLOWED);
    }

    @Test
    void resolve_blank_reason_ko() {
        // given
        givenCommission("DISPUTED");
        givenDispute("UNDER_REVIEW");

        // when / then
        assertRejected(() -> service.resolve(staffId, disputeId, resolution(DisputeOutcome.CONTINUE, "  ")),
            AppErrorMessage.DISPUTE_RESOLUTION_INVALID);
        verify(repository, never()).findWithLockByCommissionId(anyLong());
    }

    @Test
    void resolve_twice_ko() {
        // given
        givenCommission("CANCELLED");
        givenDispute("RESOLVED");

        // when / then
        assertRejected(() -> service.resolve(staffId, disputeId,
            resolution(DisputeOutcome.CANCEL_NO_REFUND, "Again")), AppErrorMessage.DISPUTE_ACTION_NOT_ALLOWED);
    }

    @Test
    void dispute_not_found_ko() {
        // given
        when(disputeRepository.findCommissionIdByDisputeId(disputeId)).thenReturn(Optional.empty());

        // when / then
        assertRejected(() -> service.review(staffId, disputeId), AppErrorMessage.DISPUTE_NOT_FOUND);
    }

    // Every rejection happens before anything is written or audited.
    private void assertRejected(Runnable action, AppErrorMessage error) {
        assertThatThrownBy(action::run)
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(error);
        verify(disputeRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any());
    }

    private Commission givenCommission(String status) {
        var commission = new Commission(customerId, artistId, "Portrait of my cat", status, "Buyer", "Painter",
            now(), null, null);
        commission.setCommissionId(commissionId);
        when(repository.findWithLockByCommissionId(commissionId)).thenReturn(Optional.of(commission));
        when(repository.findById(commissionId)).thenReturn(Optional.of(commission));
        when(repository.saveAndFlush(commission)).thenReturn(commission);
        return commission;
    }

    private CommissionDispute givenDispute(String status) {
        var dispute = new CommissionDispute(commissionId, customerId, "No reply for two weeks", status, now());
        dispute.setDisputeId(disputeId);
        when(disputeRepository.findCommissionIdByDisputeId(disputeId)).thenReturn(Optional.of(commissionId));
        when(disputeRepository.findById(disputeId)).thenReturn(Optional.of(dispute));
        return dispute;
    }

    private void givenOpenedFrom(String status) {
        var opened = new CommissionEvent(commissionId, "DISPUTE_OPENED", status, "DISPUTED", disputeId, customerId,
            null, now(), null);
        when(eventRepository.findFirstByCommissionIdAndEventTypeAndRelatedIdOrderByEventIdDesc(commissionId,
            "DISPUTE_OPENED", disputeId)).thenReturn(Optional.of(opened));
    }

    private void givenAcceptedQuote() {
        var quote = new CommissionQuote(commissionId, 1, "Full-body portrait", "3 weeks",
            new BigDecimal("100001.0000"), new BigDecimal("10001.0000"), "VND", "ACCEPTED", now());
        quote.setQuoteId(quoteId);
        when(quoteRepository.findByCommissionIdAndStatus(commissionId, "ACCEPTED")).thenReturn(List.of(quote));
    }

    private Payment payment(int installment, String status) {
        var payment = new Payment(null, quoteId, new BigDecimal("55000.0000"), "VND", status, null, now(), null);
        payment.setPaymentId(100L + installment);
        payment.setInstallment(installment);
        return payment;
    }

    private DisputeResolution resolution(DisputeOutcome outcome, String reason) {
        return DisputeResolution.builder().withOutcome(outcome).withReason(reason).build();
    }

    private Artist artist() {
        return Artist.builder()
            .withArtistId(artistId)
            .withUserId(artistUserId)
            .withDisplayName("Painter")
            .withStatus("ACTIVE")
            .withAcceptingCommissions(true)
            .build();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
