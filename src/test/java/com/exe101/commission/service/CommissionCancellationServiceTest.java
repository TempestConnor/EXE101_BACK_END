package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.audit.service.AuditService;
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
import com.exe101.entitlement.service.EntitlementService;
import com.exe101.entity.CancellationRequest;
import com.exe101.entity.Commission;
import com.exe101.entity.CommissionEvent;
import com.exe101.entity.CommissionQuote;
import com.exe101.entity.Payment;
import com.exe101.entity.RefundRequest;
import com.exe101.entity.RefundTransfer;
import com.exe101.fulfillment.service.FulfillmentService;
import com.exe101.ledger.model.RefundMovement;
import com.exe101.ledger.service.LedgerService;
import com.exe101.order.service.OrderService;
import com.exe101.proceeds.model.Proceeds;
import com.exe101.proceeds.model.ProceedsStatus;
import com.exe101.proceeds.service.ProceedsService;
import com.exe101.refund.mapper.RefundEntityMapperImpl;
import com.exe101.refund.repository.RefundRequestRepository;
import com.exe101.refund.repository.RefundTransferRepository;
import com.exe101.refund.repository.ShippingRefundRepository;
import com.exe101.refund.service.RefundServiceImpl;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The cancellation runs over the real Commission refund and refund services, so each case checks the offer, the
// transfer and the ledger movement it writes. The example is the F17b Quote: price 100 001 and tax 10 001, so the
// first payment of 55 002 holds an Artist share of 50 001 and a tax half of 5 001.
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
    private static long refundRequestId = 61L;
    private static long proceedsId = 501L;
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
    @Mock
    private RefundRequestRepository refundRepository;
    @Mock
    private RefundTransferRepository transferRepository;
    @Mock
    private ShippingRefundRepository shippingRepository;
    @Mock
    private OrderService orderService;
    @Mock
    private FulfillmentService fulfillmentService;
    @Mock
    private EntitlementService entitlementService;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private ProceedsService proceedsService;
    @Mock
    private AuditService auditService;
    private CommissionCancellationServiceImpl service;
    private List<RefundRequest> refunds;
    private List<RefundTransfer> savedTransfers;

    @BeforeEach
    void setUp() {
        var cancellations = new CommissionCancellations(repository, quoteRepository, paymentRepository,
            eventRepository);
        var refundService = new RefundServiceImpl(refundRepository, transferRepository, shippingRepository,
            new RefundEntityMapperImpl(), orderService, fulfillmentService, proceedsService, entitlementService,
            ledgerService, auditService);
        var commissionRefunds = new CommissionRefundServiceImpl(repository, quoteRepository, paymentRepository,
            eventRepository, artistService, refundService, proceedsService);
        service = new CommissionCancellationServiceImpl(repository, cancellationRepository, quoteRepository,
            paymentRepository, eventRepository, Mappers.getMapper(CommissionEntityMapper.class),
            Mappers.getMapper(CommissionCancellationEntityMapper.class), artistService, cancellations,
            commissionRefunds);
        refunds = new ArrayList<>();
        savedTransfers = new ArrayList<>();
        when(artistService.get(artistId)).thenReturn(artist("ACTIVE"));
        when(cancellationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CancellationRequest request = invocation.getArgument(0);

            if (request.getCancellationId() == null) {
                request.setCancellationId(cancellationId);
            }

            return request;
        });
        when(refundRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            RefundRequest saved = invocation.getArgument(0);

            if (saved.getRefundRequestId() == null) {
                saved.setRefundRequestId(refundRequestId);
                refunds.add(saved);
            }

            return saved;
        });
        when(refundRepository.findWithLockByCommissionIdAndStatus(commissionId, "REQUESTED"))
            .thenAnswer(invocation -> refunds.stream().filter(refund -> "REQUESTED".equals(refund.getStatus()))
                .findFirst());
        when(refundRepository.findWithLockByRefundRequestId(refundRequestId))
            .thenAnswer(invocation -> refunds.stream().findFirst());
        when(transferRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            RefundTransfer saved = invocation.getArgument(0);
            saved.setRefundTransferId(800L);
            savedTransfers.add(saved);
            return saved;
        });
        when(transferRepository.sumAmountByPaymentId(anyLong())).thenReturn(BigDecimal.ZERO);
        when(transferRepository.findByRefundRequestIdIn(any())).thenAnswer(invocation -> List.copyOf(savedTransfers));
        when(shippingRepository.findByRefundRequestIdIn(any())).thenReturn(List.of());
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
        verify(refundRepository, never()).saveAndFlush(any());
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
        var result = service.request(customerId, commissionId, " The deadline no longer works ", null);
        var saved = ArgumentCaptor.forClass(CancellationRequest.class);
        verify(cancellationRepository).saveAndFlush(saved.capture());
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        // The Customer's request waits for the Artist's refund offer.
        assertAll(
            () -> assertThat(result.getCancellationId()).isEqualTo(cancellationId),
            () -> assertThat(result.getStatus()).isEqualTo(CancellationStatus.PENDING),
            () -> assertThat(result.getRefundPercent()).isNull(),
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
        verify(refundRepository, never()).saveAndFlush(any());
    }

    @Test
    void artist_request_with_percentage_opens_offer_ok() {
        // given
        var commission = givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);

        // when
        var result = service.request(artistUserId, commissionId, " I can no longer finish it ", 70);
        var events = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository, times(2)).saveAndFlush(events.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(CancellationStatus.PENDING),
            () -> assertThat(result.getRefundPercent()).isEqualTo(70),
            () -> assertThat(commission.getStatus()).isEqualTo("IN_PROGRESS"),
            () -> assertThat(refunds).singleElement().satisfies(refund -> assertAll(
                () -> assertThat(refund.getCommissionId()).isEqualTo(commissionId),
                () -> assertThat(refund.getRequestedByUserId()).isEqualTo(customerId),
                () -> assertThat(refund.getStatus()).isEqualTo("REQUESTED"),
                () -> assertThat(refund.getRefundPercent()).isEqualTo(70),
                () -> assertThat(refund.getReason()).isEqualTo("I can no longer finish it"))),
            () -> assertThat(events.getAllValues()).extracting(CommissionEvent::getEventType)
                .containsExactly("CANCELLATION_REQUESTED", "REFUND_OFFERED"),
            () -> assertThat(events.getAllValues().getLast().getDetails()).isEqualTo("percent=70")
        );
        verify(transferRepository, never()).saveAndFlush(any());
        verify(proceedsService, never()).refundQuoteShare(anyLong(), any(), any());
    }

    @Test
    void artist_request_without_percentage_ko() {
        // given
        givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);

        // when / then
        assertRejected(() -> service.request(artistUserId, commissionId, "Too busy", null),
            AppErrorMessage.REFUND_PERCENT_INVALID);
    }

    @Test
    void customer_request_with_percentage_ko() {
        // given
        givenCommission("IN_PROGRESS");

        // when / then
        assertRejected(() -> service.request(customerId, commissionId, "Changed my mind", 100),
            AppErrorMessage.REFUND_PERCENT_INVALID);
    }

    @Test
    void percentage_out_of_range_ko() {
        // given
        givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRequest(customerId, "PENDING");

        // when / then
        assertRejected(() -> service.request(artistUserId, commissionId, "Too busy", 101),
            AppErrorMessage.REFUND_PERCENT_INVALID);
        assertRejected(() -> service.offerRefund(artistUserId, commissionId, cancellationId, -1),
            AppErrorMessage.REFUND_PERCENT_INVALID);
        verify(repository, never()).findWithLockByCommissionId(anyLong());
    }

    @Test
    void request_cancellation_after_approval_ok() {
        // given
        var commission = givenCommission("AWAITING_PAYMENT");
        givenHeldFirstPayment(ProceedsStatus.HELD);

        // when
        var result = service.request(artistUserId, commissionId, "Customer stopped answering", 0);

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(CancellationStatus.PENDING),
            () -> assertThat(result.getRequestedByUserId()).isEqualTo(artistUserId),
            () -> assertThat(result.getRefundPercent()).isZero(),
            () -> assertThat(commission.getStatus()).isEqualTo("AWAITING_PAYMENT")
        );
    }

    @Test
    void request_cancellation_before_payment_ko() {
        // given
        givenCommission("QUOTED");

        // when / then
        assertRejected(() -> service.request(customerId, commissionId, "Changed my mind", null),
            AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
    }

    @Test
    void request_cancellation_blank_reason_ko() {
        // given
        givenCommission("IN_PROGRESS");

        // when / then
        assertRejected(() -> service.request(customerId, commissionId, "   ", null),
            AppErrorMessage.COMMISSION_INVALID);
        verify(repository, never()).findWithLockByCommissionId(anyLong());
    }

    @Test
    void request_cancellation_already_pending_ko() {
        // given
        givenCommission("IN_PROGRESS");
        when(cancellationRepository.existsByCommissionIdAndStatus(commissionId, "PENDING")).thenReturn(true);

        // when / then
        assertRejected(() -> service.request(artistUserId, commissionId, "Too busy", 50),
            AppErrorMessage.CANCELLATION_PENDING);
    }

    @Test
    void request_cancellation_closed_commission_ko() {
        // given
        givenCommission("COMPLETED");

        // when / then
        assertRejected(() -> service.request(customerId, commissionId, "Changed my mind", null),
            AppErrorMessage.COMMISSION_CLOSED);
    }

    @Test
    void request_cancellation_disputed_ko() {
        // given
        givenCommission("DISPUTED");

        // when / then
        assertRejected(() -> service.request(customerId, commissionId, "Changed my mind", null),
            AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
    }

    @Test
    void artist_offers_on_customer_request_ok() {
        // given
        givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRequest(customerId, "PENDING");

        // when
        var result = service.offerRefund(artistUserId, commissionId, cancellationId, 30);
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(CancellationStatus.PENDING),
            () -> assertThat(result.getRefundPercent()).isEqualTo(30),
            () -> assertThat(refunds).singleElement().satisfies(refund -> assertAll(
                () -> assertThat(refund.getRefundPercent()).isEqualTo(30),
                () -> assertThat(refund.getStatus()).isEqualTo("REQUESTED"),
                () -> assertThat(refund.getRequestedByUserId()).isEqualTo(customerId))),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("REFUND_OFFERED"),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(artistUserId)
        );
    }

    @Test
    void offer_twice_ko() {
        // given
        givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRequest(customerId, "PENDING");
        givenOffer(30);

        // when / then
        assertRejected(() -> service.offerRefund(artistUserId, commissionId, cancellationId, 50),
            AppErrorMessage.COMMISSION_REFUND_ACTION_NOT_ALLOWED);
    }

    @Test
    void customer_offers_ko() {
        // given
        givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRequest(customerId, "PENDING");

        // when / then
        assertRejected(() -> service.offerRefund(customerId, commissionId, cancellationId, 100),
            AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void artist_accepts_customer_request_ko() {
        // given
        givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRequest(customerId, "PENDING");

        // when / then
        // The Artist answers the Customer's request with an offer, which the Customer then accepts.
        assertRejected(() -> service.accept(artistUserId, commissionId, cancellationId, null),
            AppErrorMessage.COMMISSION_REFUND_ACTION_NOT_ALLOWED);
    }

    @Test
    void customer_accepts_before_offer_ko() {
        // given
        givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRequest(customerId, "PENDING");

        // when / then
        assertRejected(() -> service.accept(customerId, commissionId, cancellationId, null),
            AppErrorMessage.COMMISSION_REFUND_ACTION_NOT_ALLOWED);
    }

    @Test
    void accept_offer_cancels_and_refunds_ok() {
        // given
        var commission = givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        var request = givenRequest(artistUserId, "PENDING");
        var offer = givenOffer(70);

        // when
        var result = service.accept(customerId, commissionId, cancellationId, " Agreed ");
        var movement = ArgumentCaptor.forClass(RefundMovement.class);
        verify(ledgerService).recordRefundApproved(movement.capture());
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        // 70% of 50 001 = 35 000.7 -> 35 001; 70% of 5 001 = 3 500.7 -> 3 501. The Artist keeps 15 000.
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(CancellationStatus.ACCEPTED),
            () -> assertThat(result.getRefundPercent()).isEqualTo(70),
            () -> assertThat(request.getStatus()).isEqualTo("ACCEPTED"),
            () -> assertThat(commission.getStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("CANCELLATION_ACCEPTED"),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(customerId),
            () -> assertThat(event.getValue().getDetails()).isEqualTo("Agreed"),
            () -> assertThat(offer.getStatus()).isEqualTo("APPROVED"),
            () -> assertThat(offer.getDecidedAt()).isNotNull(),
            () -> assertThat(offer.getDecidedByStaffId()).isNull(),
            () -> assertThat(savedTransfers).singleElement().satisfies(transfer -> assertAll(
                () -> assertThat(transfer.getAmount()).isEqualByComparingTo("38502"),
                () -> assertThat(transfer.getPaymentId()).isEqualTo(101L),
                () -> assertThat(transfer.getStatus()).isEqualTo("PENDING"))),
            () -> assertThat(movement.getValue().getProceeds()).isEqualByComparingTo("35001"),
            () -> assertThat(movement.getValue().getTax()).isEqualByComparingTo("3501"),
            () -> assertThat(movement.getValue().getQuoteId()).isEqualTo(quoteId),
            () -> assertThat(movement.getValue().getArtistId()).isEqualTo(artistId)
        );
        verify(proceedsService).refundQuoteShare(eq(proceedsId), eq(new BigDecimal("35001.0000")), any());
    }

    @Test
    void half_up_rounding_boundary_ok() {
        // given
        givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRequest(artistUserId, "PENDING");
        givenOffer(50);

        // when
        service.accept(customerId, commissionId, cancellationId, null);

        // then
        // 50% of 50 001 = 25 000.5 -> 25 001; 50% of 5 001 = 2 500.5 -> 2 501: the half goes to the Customer.
        assertThat(savedTransfers).singleElement().satisfies(transfer ->
            assertThat(transfer.getAmount()).isEqualByComparingTo("27502"));
        verify(proceedsService).refundQuoteShare(eq(proceedsId), eq(new BigDecimal("25001.0000")), any());
    }

    @Test
    void full_refund_voids_proceeds_ok() {
        // given
        givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRequest(artistUserId, "PENDING");
        givenOffer(100);

        // when
        service.accept(customerId, commissionId, cancellationId, null);

        // then
        assertThat(savedTransfers).singleElement().satisfies(transfer ->
            assertThat(transfer.getAmount()).isEqualByComparingTo("55002"));
        // The whole share is refunded, which makes the proceeds VOID with nothing to release.
        verify(proceedsService).refundQuoteShare(eq(proceedsId), eq(new BigDecimal("50001.0000")), any());
    }

    @Test
    void zero_percent_rejects_refund_and_releases_ok() {
        // given
        var commission = givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRequest(artistUserId, "PENDING");
        var offer = givenOffer(0);

        // when
        service.accept(customerId, commissionId, cancellationId, null);

        // then
        assertAll(
            () -> assertThat(commission.getStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(offer.getStatus()).isEqualTo("REJECTED"),
            () -> assertThat(offer.getRefundPercent()).isZero()
        );
        verify(transferRepository, never()).saveAndFlush(any());
        verify(ledgerService, never()).recordRefundApproved(any());
        verify(proceedsService).refundQuoteShare(eq(proceedsId), eq(new BigDecimal("0.0000")), any());
    }

    @Test
    void accept_cancellation_cancels_pending_final_payment_ok() {
        // given
        var commission = givenCommission("AWAITING_PAYMENT");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        var last = givenPayment(2, "PENDING");
        when(paymentRepository.findByQuoteIdAndStatus(quoteId, "PENDING")).thenReturn(List.of(last));
        givenRequest(artistUserId, "PENDING");
        givenOffer(100);

        // when
        service.accept(customerId, commissionId, cancellationId, null);

        // then
        assertAll(
            () -> assertThat(commission.getStatus()).isEqualTo("CANCELLED"),
            () -> assertThat(last.getStatus()).isEqualTo("CANCELLED")
        );
        verify(paymentRepository).saveAndFlush(last);
    }

    @Test
    void accept_own_cancellation_ko() {
        // given
        givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRequest(artistUserId, "PENDING");
        givenOffer(70);

        // when / then
        assertRejected(() -> service.accept(artistUserId, commissionId, cancellationId, null),
            AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void held_share_not_held_ko() {
        // given
        givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.RESERVED);
        givenRequest(artistUserId, "PENDING");
        givenOffer(70);

        // when / then
        assertRejected(() -> service.accept(customerId, commissionId, cancellationId, null),
            AppErrorMessage.COMMISSION_REFUND_NOT_ALLOWED);
        verify(proceedsService, never()).refundQuoteShare(anyLong(), any(), any());
        verify(ledgerService, never()).recordRefundApproved(any());
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
    void reject_closes_offer_ok() {
        // given
        var commission = givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRequest(artistUserId, "PENDING");
        var offer = givenOffer(10);

        // when
        service.reject(customerId, commissionId, cancellationId, "Ten percent breaks your terms");

        // then
        assertAll(
            () -> assertThat(commission.getStatus()).isEqualTo("IN_PROGRESS"),
            () -> assertThat(offer.getStatus()).isEqualTo("REJECTED"),
            () -> assertThat(offer.getDecidedAt()).isNotNull(),
            () -> assertThat(offer.getDecisionReason()).isEqualTo("The cancellation request was rejected.")
        );
        verify(transferRepository, never()).saveAndFlush(any());
        verify(proceedsService, never()).refundQuoteShare(anyLong(), any(), any());
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
    void withdraw_closes_offer_ok() {
        // given
        givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRequest(customerId, "PENDING");
        var offer = givenOffer(20);

        // when
        service.withdraw(customerId, commissionId, cancellationId);

        // then
        assertAll(
            () -> assertThat(offer.getStatus()).isEqualTo("REJECTED"),
            () -> assertThat(offer.getDecisionReason()).isEqualTo("The cancellation request was withdrawn.")
        );
        verify(transferRepository, never()).saveAndFlush(any());
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
        givenRequest(artistUserId, "REJECTED");

        // when / then
        assertRejected(() -> service.accept(customerId, commissionId, cancellationId, null),
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
        givenHeldFirstPayment(ProceedsStatus.HELD);
        when(artistService.get(artistId)).thenReturn(artist("SUSPENDED"));

        // when
        var result = service.request(artistUserId, commissionId, "Leaving the team", 100);

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(CancellationStatus.PENDING),
            () -> assertThat(result.getRequestedByUserId()).isEqualTo(artistUserId),
            () -> assertThat(result.getRefundPercent()).isEqualTo(100)
        );
    }

    @Test
    void deactivated_artist_offers_ok() {
        // given
        givenCommission("IN_PROGRESS");
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRequest(customerId, "PENDING");
        when(artistService.get(artistId)).thenReturn(artist("SUSPENDED"));

        // when
        var result = service.offerRefund(artistUserId, commissionId, cancellationId, 60);

        // then
        assertThat(result.getRefundPercent()).isEqualTo(60);
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
        verify(refundRepository, never()).saveAndFlush(any());
        verify(transferRepository, never()).saveAndFlush(any());
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

    private void givenHeldFirstPayment(ProceedsStatus proceedsStatus) {
        givenAcceptedQuote();
        givenPayment(1, "SUCCEEDED");
        when(proceedsService.findLockedByQuote(quoteId)).thenReturn(List.of(Proceeds.builder()
            .withProceedsId(proceedsId)
            .withArtistId(artistId)
            .withQuoteId(quoteId)
            .withInstallment(1)
            .withAmount(new BigDecimal("50001.0000"))
            .withCurrencyCode("VND")
            .withStatus(proceedsStatus)
            .build()));
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

    // The Artist's offer already made, as a REQUESTED refund.
    private RefundRequest givenOffer(int percent) {
        var offer = new RefundRequest(null, commissionId, customerId, "The deadline no longer works", "REQUESTED",
            now(), null, null, null);
        offer.setRefundRequestId(refundRequestId);
        offer.setRefundPercent(percent);
        refunds.add(offer);
        return offer;
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
