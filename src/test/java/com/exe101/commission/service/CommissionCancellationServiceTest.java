package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.commission.mapper.CommissionCancellationEntityMapper;
import com.exe101.commission.mapper.CommissionEntityMapper;
import com.exe101.commission.model.CancellationStatus;
import com.exe101.commission.model.CommissionStatus;
import com.exe101.commission.repository.CancellationRequestRepository;
import com.exe101.commission.repository.CommissionEventRepository;
import com.exe101.commission.repository.CommissionQuoteRepository;
import com.exe101.commission.repository.CommissionRepository;
import com.exe101.commission.repository.QuotePaymentRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.CancellationRequest;
import com.exe101.entity.Commission;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommissionCancellationServiceTest {
    private static long customerId = 42L;
    private static long artistUserId = 50L;
    private static long strangerId = 77L;
    private static long artistId = 9L;
    private static long commissionId = 3L;
    private static long quoteId = 21L;
    private static long cancellationId = 81L;
    @Mock
    private CommissionRepository repository;
    @Mock
    private CancellationRequestRepository cancellationRepository;
    @Mock
    private CommissionQuoteRepository quoteRepository;
    @Mock
    private QuotePaymentRepository paymentRepository;
    @Mock
    private CommissionEventRepository eventRepository;
    @Mock
    private ArtistService artistService;
    private CommissionCancellationServiceImpl service;

    @BeforeEach
    void setUp() {
        var cancellations = new CommissionCancellations(repository, quoteRepository, paymentRepository,
            eventRepository);
        service = new CommissionCancellationServiceImpl(repository, cancellationRepository, quoteRepository,
            paymentRepository, eventRepository, Mappers.getMapper(CommissionEntityMapper.class),
            Mappers.getMapper(CommissionCancellationEntityMapper.class), artistService, cancellations);
        when(artistService.get(artistId)).thenReturn(artist("ACTIVE"));
        when(cancellationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CancellationRequest request = invocation.getArgument(0);

            if (request.getCancellationId() == null) {
                request.setCancellationId(cancellationId);
            }

            return request;
        });
        when(quoteRepository.findByCommissionIdAndStatus(commissionId, "OFFERED")).thenReturn(List.of());
        when(quoteRepository.findByCommissionIdAndStatus(commissionId, "ACCEPTED")).thenReturn(List.of());
    }

    @Test
    void cancel_before_first_payment_ok() {
        // given
        var commission = givenCommission("QUOTED");
        var offered = quote("OFFERED");
        when(quoteRepository.findByCommissionIdAndStatus(commissionId, "OFFERED")).thenReturn(List.of(offered));

        // when
        var result = service.cancel(customerId, commissionId, " Changed my mind ");
        var events = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository, times(2)).saveAndFlush(events.capture());
        var cancelled = events.getAllValues().getLast();

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(CommissionStatus.CANCELLED),
            () -> assertThat(commission.getStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(offered.getStatus()).isEqualTo("WITHDRAWN"),
            () -> assertThat(events.getAllValues().getFirst().getEventType()).isEqualTo("QUOTE_WITHDRAWN"),
            () -> assertThat(cancelled.getEventType()).isEqualTo("COMMISSION_CANCELLED"),
            () -> assertThat(cancelled.getFromStatus()).isEqualTo("QUOTED"),
            () -> assertThat(cancelled.getToStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(cancelled.getActorUserId()).isEqualTo(customerId),
            () -> assertThat(cancelled.getDetails()).isEqualTo("Changed my mind")
        );
        verify(cancellationRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancel_before_first_payment_by_artist_ok() {
        // given
        var commission = givenCommission("AWAITING_PAYMENT");
        givenAcceptedQuote();
        var payment = givenPayment(1, "PENDING");
        when(paymentRepository.findByQuoteIdAndStatus(quoteId, "PENDING")).thenReturn(List.of(payment));

        // when
        service.cancel(artistUserId, commissionId, "Cannot take this on");
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(commission.getStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(payment.getStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(payment.getSucceededAt()).isNull(),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("AWAITING_PAYMENT"),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(artistUserId)
        );
        verify(paymentRepository).saveAndFlush(payment);
    }

    @Test
    void cancel_after_first_payment_ko() {
        // given
        givenCommission("IN_PROGRESS");

        // when / then
        assertRejected(() -> service.cancel(customerId, commissionId, "Changed my mind"),
            AppErrorMessage.CANCELLATION_REQUEST_REQUIRED);
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancel_non_participant_ko() {
        // given
        givenCommission("REQUESTED");

        // when / then
        assertRejected(() -> service.cancel(strangerId, commissionId, "Changed my mind"),
            AppErrorMessage.COMMISSION_NOT_FOUND);
    }

    @Test
    void cancel_commission_not_found_ko() {
        // given
        when(repository.findWithLockByCommissionId(commissionId)).thenReturn(Optional.empty());

        // when / then
        assertRejected(() -> service.cancel(customerId, commissionId, "Changed my mind"),
            AppErrorMessage.COMMISSION_NOT_FOUND);
    }

    @Test
    void request_cancellation_ok() {
        // given
        var commission = givenCommission("IN_PROGRESS");

        // when
        var result = service.request(customerId, commissionId, " The deadline no longer works ");
        var saved = ArgumentCaptor.forClass(CancellationRequest.class);
        verify(cancellationRepository).saveAndFlush(saved.capture());
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getCancellationId()).isEqualTo(cancellationId),
            () -> assertThat(result.getStatus()).isEqualTo(CancellationStatus.PENDING),
            () -> assertThat(saved.getValue().getCommissionId()).isEqualTo(commissionId),
            () -> assertThat(saved.getValue().getRequestedByUserId()).isEqualTo(customerId),
            () -> assertThat(saved.getValue().getReason()).isEqualTo("The deadline no longer works"),
            () -> assertThat(saved.getValue().getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC),
            () -> assertThat(saved.getValue().getResolvedAt()).isNull(),
            () -> assertThat(commission.getStatus()).isEqualTo("IN_PROGRESS"),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("CANCELLATION_REQUESTED"),
            () -> assertThat(event.getValue().getRelatedId()).isEqualTo(cancellationId),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(customerId)
        );
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void request_cancellation_after_approval_ok() {
        // given
        var commission = givenCommission("AWAITING_PAYMENT");
        givenAcceptedQuote();
        givenPayment(1, "SUCCEEDED");

        // when
        var result = service.request(artistUserId, commissionId, "Customer stopped answering");

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(CancellationStatus.PENDING),
            () -> assertThat(result.getRequestedByUserId()).isEqualTo(artistUserId),
            () -> assertThat(commission.getStatus()).isEqualTo("AWAITING_PAYMENT")
        );
    }

    @Test
    void request_cancellation_before_payment_ko() {
        // given
        givenCommission("QUOTED");

        // when / then
        assertRejected(() -> service.request(customerId, commissionId, "Changed my mind"),
            AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
    }

    @Test
    void request_cancellation_blank_reason_ko() {
        // given
        givenCommission("IN_PROGRESS");

        // when / then
        assertRejected(() -> service.request(customerId, commissionId, "   "), AppErrorMessage.COMMISSION_INVALID);
        verify(repository, never()).findWithLockByCommissionId(anyLong());
    }

    @Test
    void request_cancellation_already_pending_ko() {
        // given
        givenCommission("IN_PROGRESS");
        when(cancellationRepository.existsByCommissionIdAndStatus(commissionId, "PENDING")).thenReturn(true);

        // when / then
        assertRejected(() -> service.request(artistUserId, commissionId, "Too busy"),
            AppErrorMessage.CANCELLATION_PENDING);
    }

    @Test
    void request_cancellation_closed_commission_ko() {
        // given
        givenCommission("COMPLETED");

        // when / then
        assertRejected(() -> service.request(customerId, commissionId, "Changed my mind"),
            AppErrorMessage.COMMISSION_CLOSED);
    }

    @Test
    void request_cancellation_disputed_ko() {
        // given
        givenCommission("DISPUTED");

        // when / then
        assertRejected(() -> service.request(customerId, commissionId, "Changed my mind"),
            AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
    }

    @Test
    void accept_cancellation_ok() {
        // given
        var commission = givenCommission("IN_PROGRESS");
        givenAcceptedQuote();
        var request = givenRequest(customerId, "PENDING");

        // when
        var result = service.accept(artistUserId, commissionId, cancellationId, " Agreed ");
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(CancellationStatus.ACCEPTED),
            () -> assertThat(request.getStatus()).isEqualTo("ACCEPTED"),
            () -> assertThat(request.getResolvedAt()).isNotNull(),
            () -> assertThat(commission.getStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("CANCELLATION_ACCEPTED"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("IN_PROGRESS"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(event.getValue().getRelatedId()).isEqualTo(cancellationId),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(artistUserId),
            () -> assertThat(event.getValue().getDetails()).isEqualTo("Agreed")
        );
        // No refund and no release: the paid installment stays HELD until F20 decides it.
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void accept_cancellation_cancels_pending_final_payment_ok() {
        // given
        var commission = givenCommission("AWAITING_PAYMENT");
        givenAcceptedQuote();
        var first = givenPayment(1, "SUCCEEDED");
        var last = givenPayment(2, "PENDING");
        when(paymentRepository.findByQuoteIdAndStatus(quoteId, "PENDING")).thenReturn(List.of(last));
        givenRequest(artistUserId, "PENDING");

        // when
        service.accept(customerId, commissionId, cancellationId, null);

        // then
        assertAll(
            () -> assertThat(commission.getStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(last.getStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(first.getStatus()).isEqualTo("SUCCEEDED")
        );
        verify(paymentRepository).saveAndFlush(last);
        verify(paymentRepository, never()).saveAndFlush(first);
    }

    @Test
    void accept_own_cancellation_ko() {
        // given
        givenCommission("IN_PROGRESS");
        givenRequest(customerId, "PENDING");

        // when / then
        assertRejected(() -> service.accept(customerId, commissionId, cancellationId, null),
            AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void reject_cancellation_ok() {
        // given
        var commission = givenCommission("SUBMITTED_FOR_REVIEW");
        var request = givenRequest(customerId, "PENDING");

        // when
        var result = service.reject(artistUserId, commissionId, cancellationId, "The work is nearly done");
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(CancellationStatus.REJECTED),
            () -> assertThat(request.getResolvedAt()).isNotNull(),
            () -> assertThat(commission.getStatus()).isEqualTo("SUBMITTED_FOR_REVIEW"),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("CANCELLATION_REJECTED"),
            () -> assertThat(event.getValue().getDetails()).isEqualTo("The work is nearly done")
        );
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void withdraw_cancellation_ok() {
        // given
        var commission = givenCommission("IN_PROGRESS");
        var request = givenRequest(customerId, "PENDING");

        // when
        var result = service.withdraw(customerId, commissionId, cancellationId);
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(CancellationStatus.WITHDRAWN),
            () -> assertThat(request.getStatus()).isEqualTo("WITHDRAWN"),
            () -> assertThat(commission.getStatus()).isEqualTo("IN_PROGRESS"),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("CANCELLATION_WITHDRAWN")
        );
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void withdraw_by_other_party_ko() {
        // given
        givenCommission("IN_PROGRESS");
        givenRequest(customerId, "PENDING");

        // when / then
        assertRejected(() -> service.withdraw(artistUserId, commissionId, cancellationId),
            AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void respond_to_resolved_cancellation_ko() {
        // given
        givenCommission("IN_PROGRESS");
        givenRequest(customerId, "REJECTED");

        // when / then
        assertRejected(() -> service.accept(artistUserId, commissionId, cancellationId, null),
            AppErrorMessage.CANCELLATION_NOT_PENDING);
    }

    @Test
    void cancellation_request_not_found_ko() {
        // given
        givenCommission("IN_PROGRESS");
        when(cancellationRepository.findByCancellationIdAndCommissionId(cancellationId, commissionId))
            .thenReturn(Optional.empty());

        // when / then
        assertRejected(() -> service.reject(artistUserId, commissionId, cancellationId, null),
            AppErrorMessage.CANCELLATION_REQUEST_NOT_FOUND);
    }

    @Test
    void deactivated_artist_requests_cancellation_ok() {
        // given
        givenCommission("IN_PROGRESS");
        when(artistService.get(artistId)).thenReturn(artist("SUSPENDED"));

        // when
        var result = service.request(artistUserId, commissionId, "Leaving the team");

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(CancellationStatus.PENDING),
            () -> assertThat(result.getRequestedByUserId()).isEqualTo(artistUserId)
        );
    }

    // Every rejection happens before anything is written.
    private void assertRejected(Runnable action, AppErrorMessage error) {
        assertThatThrownBy(action::run)
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(error);
        verify(cancellationRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
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

    private CommissionQuote givenAcceptedQuote() {
        var quote = quote("ACCEPTED");
        when(quoteRepository.findByCommissionIdAndStatus(commissionId, "ACCEPTED")).thenReturn(List.of(quote));
        return quote;
    }

    private CommissionQuote quote(String status) {
        var quote = new CommissionQuote(commissionId, 1, "Full-body portrait", "3 weeks",
            new BigDecimal("100001.0000"), new BigDecimal("10001.0000"), "VND", status, now());
        quote.setQuoteId(quoteId);
        return quote;
    }

    private Payment givenPayment(int installment, String status) {
        var payment = new Payment(null, quoteId, new BigDecimal(installment == 1 ? "55002.0000" : "55000.0000"),
            "VND", status, null, now(), null);
        payment.setPaymentId(100L + installment);
        payment.setInstallment(installment);

        if ("SUCCEEDED".equals(status)) {
            payment.setSucceededAt(now());
        }

        when(paymentRepository.findByQuoteIdAndInstallment(quoteId, installment)).thenReturn(Optional.of(payment));
        return payment;
    }

    private CancellationRequest givenRequest(long requestedBy, String status) {
        var request = new CancellationRequest(commissionId, requestedBy, "The deadline no longer works", status,
            now());
        request.setCancellationId(cancellationId);
        when(cancellationRepository.findByCancellationIdAndCommissionId(cancellationId, commissionId))
            .thenReturn(Optional.of(request));
        return request;
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
