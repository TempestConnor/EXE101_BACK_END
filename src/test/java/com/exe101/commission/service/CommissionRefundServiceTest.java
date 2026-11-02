package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.commission.model.CommissionRefundDecision;
import com.exe101.commission.model.CommissionRefundStage;
import com.exe101.commission.repository.CommissionEventRepository;
import com.exe101.commission.repository.CommissionQuoteRepository;
import com.exe101.commission.repository.CommissionRepository;
import com.exe101.commission.repository.QuotePaymentRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entitlement.service.EntitlementService;
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
import com.exe101.refund.model.RefundStatus;
import com.exe101.refund.model.TransferStatus;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The Commission refund runs over the real refund service, so each case checks the transfer, the request and the
// ledger movement it writes. The example is the F17b Quote: price 100 001 and tax 10 001, so the first payment of
// 55 002 holds an Artist share of 50 001 and a tax half of 5 001.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommissionRefundServiceTest {
    private static long customerId = 42L;
    private static long artistUserId = 50L;
    private static long strangerId = 77L;
    private static long artistId = 9L;
    private static long commissionId = 3L;
    private static long quoteId = 21L;
    private static long paymentId = 101L;
    private static long proceedsId = 501L;
    private static long refundRequestId = 61L;
    private static long staffId = 5L;
    @Mock
    private CommissionRepository repository;
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
    private CommissionPaymentService commissionPaymentService;
    @Mock
    private ProceedsService proceedsService;
    @Mock
    private AuditService auditService;
    private CommissionRefundServiceImpl service;
    private List<RefundRequest> savedRequests;
    private List<RefundTransfer> savedTransfers;
    private List<CommissionEvent> savedEvents;

    @BeforeEach
    void setUp() {
        var refundService = new RefundServiceImpl(refundRepository, transferRepository, shippingRepository,
            new RefundEntityMapperImpl(), orderService, fulfillmentService, proceedsService, entitlementService,
            ledgerService, auditService, commissionPaymentService);
        service = new CommissionRefundServiceImpl(repository, quoteRepository, paymentRepository, eventRepository,
            artistService, refundService, proceedsService, auditService);
        savedRequests = new ArrayList<>();
        savedTransfers = new ArrayList<>();
        savedEvents = new ArrayList<>();
        when(artistService.get(artistId)).thenReturn(artist("ACTIVE"));
        when(refundRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            RefundRequest saved = invocation.getArgument(0);

            if (saved.getRefundRequestId() == null) {
                saved.setRefundRequestId(refundRequestId);
            }

            savedRequests.add(saved);
            return saved;
        });
        when(transferRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            RefundTransfer saved = invocation.getArgument(0);
            saved.setRefundTransferId(800L);
            savedTransfers.add(saved);
            return saved;
        });
        when(transferRepository.sumAmountByPaymentId(anyLong())).thenReturn(BigDecimal.ZERO);
        when(transferRepository.findByRefundRequestIdIn(any())).thenAnswer(invocation -> List.copyOf(savedTransfers));
        when(shippingRepository.findByRefundRequestIdIn(any())).thenReturn(List.of());
        when(eventRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CommissionEvent saved = invocation.getArgument(0);
            savedEvents.add(saved);
            return saved;
        });
        when(eventRepository.findFirstByCommissionIdAndEventTypeAndRelatedIdOrderByEventIdDesc(anyLong(), anyString(),
            any())).thenAnswer(invocation -> savedEvents.stream()
                .filter(event -> event.getEventType().equals(invocation.getArgument(1)))
                .findFirst());
        when(quoteRepository.findByCommissionIdAndStatus(commissionId, "ACCEPTED")).thenReturn(List.of());
    }

    @Test
    void accepted_cancellation_opens_refund_ok() {
        // given
        givenCommission();
        givenHeldFirstPayment(ProceedsStatus.HELD);
        var cancelledAt = now();

        // when
        service.openOnCancellation(commissionId, artistUserId, null, "The deadline no longer works", cancelledAt);

        // then
        assertAll(
            () -> assertThat(savedRequests).singleElement().satisfies(request -> assertAll(
                () -> assertThat(request.getCommissionId()).isEqualTo(commissionId),
                () -> assertThat(request.getOrderItemId()).isNull(),
                () -> assertThat(request.getRequestedByUserId()).isEqualTo(customerId),
                () -> assertThat(request.getStatus()).isEqualTo("REQUESTED"),
                () -> assertThat(request.getReason()).isEqualTo("The deadline no longer works"),
                () -> assertThat(request.getRequestedAt()).isEqualTo(cancelledAt),
                () -> assertThat(request.getRefundPercent()).isNull())),
            () -> assertThat(savedEvents).singleElement().satisfies(event -> assertAll(
                () -> assertThat(event.getEventType()).isEqualTo("REFUND_OPENED"),
                () -> assertThat(event.getRelatedId()).isEqualTo(refundRequestId),
                () -> assertThat(event.getActorUserId()).isEqualTo(artistUserId)))
        );
        verify(ledgerService, never()).recordRefundApproved(any());
    }

    @Test
    void cancel_refund_outcome_opens_refund_ok() {
        // given
        givenCommission();
        givenHeldFirstPayment(ProceedsStatus.HELD);

        // when
        service.openOnCancellation(commissionId, null, staffId, "Artist left", now());

        // then
        assertAll(
            () -> assertThat(savedRequests).singleElement().satisfies(request -> assertAll(
                () -> assertThat(request.getRequestedByUserId()).isEqualTo(customerId),
                () -> assertThat(request.getStatus()).isEqualTo("REQUESTED"))),
            () -> assertThat(savedEvents).singleElement().satisfies(event -> assertAll(
                () -> assertThat(event.getActorStaffId()).isEqualTo(staffId),
                () -> assertThat(event.getActorUserId()).isNull()))
        );
    }

    @Test
    void cancel_no_refund_outcome_releases_held_share_ok() {
        // given
        givenCommission();
        givenHeldFirstPayment(ProceedsStatus.HELD);
        var cancelledAt = now();

        // when
        service.releaseOnCancellation(commissionId, cancelledAt);

        // then
        verify(proceedsService).releaseQuote(quoteId, cancelledAt);
        verify(refundRepository, never()).saveAndFlush(any());
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancel_before_payment_opens_no_refund_ok() {
        // given
        givenCommission();
        givenAcceptedQuote();
        givenFirstPayment("CANCELLED");
        when(proceedsService.findLockedByQuote(quoteId)).thenReturn(List.of());

        // when
        service.openOnCancellation(commissionId, customerId, null, "Changed my mind", now());

        // then
        verify(refundRepository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void artist_sets_percentage_ok() {
        // given
        givenCommission();
        var request = givenRefund(null, "REQUESTED");

        // when
        var result = service.setPercent(artistUserId, commissionId, refundRequestId, 70);

        // then
        assertAll(
            () -> assertThat(request.getRefundPercent()).isEqualTo(70),
            () -> assertThat(result.getRefundPercent()).isEqualTo(70),
            () -> assertThat(result.getStatus()).isEqualTo(RefundStatus.REQUESTED),
            () -> assertThat(result.getStage()).isEqualTo(CommissionRefundStage.AWAITING_CUSTOMER),
            () -> assertThat(result.getTransfer()).isNull(),
            () -> assertThat(savedEvents).singleElement().satisfies(event -> assertAll(
                () -> assertThat(event.getEventType()).isEqualTo("REFUND_PERCENT_SET"),
                () -> assertThat(event.getDetails()).isEqualTo("percent=70"),
                () -> assertThat(event.getActorUserId()).isEqualTo(artistUserId)))
        );
        verify(transferRepository, never()).saveAndFlush(any());
        verify(proceedsService, never()).refundQuoteShare(anyLong(), any(), any());
    }

    @Test
    void deactivated_artist_sets_percentage_ok() {
        // given
        when(artistService.get(artistId)).thenReturn(artist("SUSPENDED"));
        givenCommission();
        givenRefund(null, "REQUESTED");

        // when
        var result = service.setPercent(artistUserId, commissionId, refundRequestId, 30);

        // then
        assertThat(result.getRefundPercent()).isEqualTo(30);
    }

    @Test
    void customer_sets_percentage_ko() {
        // given
        givenCommission();
        givenRefund(null, "REQUESTED");

        // when / then
        assertRejected(() -> service.setPercent(customerId, commissionId, refundRequestId, 100),
            AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void percentage_out_of_range_ko() {
        // given
        givenCommission();
        givenRefund(null, "REQUESTED");

        // when / then
        assertRejected(() -> service.setPercent(artistUserId, commissionId, refundRequestId, 101),
            AppErrorMessage.REFUND_PERCENT_INVALID);
        assertRejected(() -> service.setPercent(artistUserId, commissionId, refundRequestId, -1),
            AppErrorMessage.REFUND_PERCENT_INVALID);
        verify(repository, never()).findWithLockByCommissionId(anyLong());
    }

    @Test
    void percentage_already_set_ko() {
        // given
        givenCommission();
        givenRefund(70, "REQUESTED");

        // when / then
        assertRejected(() -> service.setPercent(artistUserId, commissionId, refundRequestId, 50),
            AppErrorMessage.COMMISSION_REFUND_ACTION_NOT_ALLOWED);
    }

    @Test
    void customer_accepts_refunds_and_releases_rest_ok() {
        // given
        givenCommission();
        givenHeldFirstPayment(ProceedsStatus.HELD);
        var request = givenRefund(70, "REQUESTED");

        // when
        var result = service.accept(customerId, commissionId, refundRequestId);
        var movement = ArgumentCaptor.forClass(RefundMovement.class);
        verify(ledgerService).recordRefundApproved(movement.capture());

        // then
        // 70% of 50 001 = 35 000.7 -> 35 001; 70% of 5 001 = 3 500.7 -> 3 501.
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(RefundStatus.APPROVED),
            () -> assertThat(result.getStage()).isNull(),
            () -> assertThat(request.getStatus()).isEqualTo("APPROVED"),
            () -> assertThat(request.getDecidedAt()).isNotNull(),
            () -> assertThat(request.getDecidedByStaffId()).isNull(),
            () -> assertThat(savedTransfers).singleElement().satisfies(transfer -> assertAll(
                () -> assertThat(transfer.getAmount()).isEqualByComparingTo("38502"),
                () -> assertThat(transfer.getPaymentId()).isEqualTo(paymentId),
                () -> assertThat(transfer.getStatus()).isEqualTo("PENDING"),
                () -> assertThat(transfer.getCurrencyCode()).isEqualTo("VND"))),
            () -> assertThat(result.getTransfer().getStatus()).isEqualTo(TransferStatus.PENDING),
            () -> assertThat(movement.getValue().getProceeds()).isEqualByComparingTo("35001"),
            () -> assertThat(movement.getValue().getTax()).isEqualByComparingTo("3501"),
            () -> assertThat(movement.getValue().getAmount()).isEqualByComparingTo("38502"),
            () -> assertThat(movement.getValue().getQuoteId()).isEqualTo(quoteId),
            () -> assertThat(movement.getValue().getOrderItemId()).isNull(),
            () -> assertThat(movement.getValue().getArtistId()).isEqualTo(artistId),
            () -> assertThat(savedEvents).extracting(CommissionEvent::getEventType).containsExactly("REFUND_ACCEPTED")
        );
        // The rest of the share, 50 001 - 35 001 = 15 000, is released by the proceeds.
        verify(proceedsService).refundQuoteShare(eq(proceedsId), eq(new BigDecimal("35001.0000")), any());
    }

    @Test
    void half_up_rounding_boundary_ok() {
        // given
        givenCommission();
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRefund(50, "REQUESTED");

        // when
        service.accept(customerId, commissionId, refundRequestId);

        // then
        // 50% of 50 001 = 25 000.5 -> 25 001; 50% of 5 001 = 2 500.5 -> 2 501: the half goes to the Customer.
        assertThat(savedTransfers).singleElement().satisfies(transfer ->
            assertThat(transfer.getAmount()).isEqualByComparingTo("27502"));
        verify(proceedsService).refundQuoteShare(eq(proceedsId), eq(new BigDecimal("25001.0000")), any());
    }

    @Test
    void full_refund_voids_proceeds_ok() {
        // given
        givenCommission();
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRefund(100, "REQUESTED");

        // when
        var result = service.accept(customerId, commissionId, refundRequestId);

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(RefundStatus.APPROVED),
            () -> assertThat(savedTransfers).singleElement().satisfies(transfer ->
                assertThat(transfer.getAmount()).isEqualByComparingTo("55002"))
        );
        // The whole share is refunded, which makes the proceeds VOID with nothing to release.
        verify(proceedsService).refundQuoteShare(eq(proceedsId), eq(new BigDecimal("50001.0000")), any());
    }

    @Test
    void zero_percent_rejects_and_releases_whole_share_ok() {
        // given
        givenCommission();
        givenHeldFirstPayment(ProceedsStatus.HELD);
        var request = givenRefund(0, "REQUESTED");

        // when
        var result = service.accept(customerId, commissionId, refundRequestId);

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(RefundStatus.REJECTED),
            () -> assertThat(result.getTransfer()).isNull(),
            () -> assertThat(request.getStatus()).isEqualTo("REJECTED"),
            () -> assertThat(request.getRefundPercent()).isZero()
        );
        verify(transferRepository, never()).saveAndFlush(any());
        verify(ledgerService, never()).recordRefundApproved(any());
        verify(proceedsService).refundQuoteShare(eq(proceedsId), eq(new BigDecimal("0.0000")), any());
    }

    @Test
    void accept_before_percentage_ko() {
        // given
        givenCommission();
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRefund(null, "REQUESTED");

        // when / then
        assertRejected(() -> service.accept(customerId, commissionId, refundRequestId),
            AppErrorMessage.COMMISSION_REFUND_ACTION_NOT_ALLOWED);
    }

    @Test
    void artist_accepts_ko() {
        // given
        givenCommission();
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRefund(70, "REQUESTED");

        // when / then
        assertRejected(() -> service.accept(artistUserId, commissionId, refundRequestId),
            AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void customer_objects_ok() {
        // given
        givenCommission();
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRefund(10, "REQUESTED");

        // when
        var result = service.object(customerId, commissionId, refundRequestId, " The sketch was never started ");

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(RefundStatus.REQUESTED),
            () -> assertThat(result.getStage()).isEqualTo(CommissionRefundStage.AWAITING_ADMIN),
            () -> assertThat(savedEvents).singleElement().satisfies(event -> assertAll(
                () -> assertThat(event.getEventType()).isEqualTo("REFUND_OBJECTED"),
                () -> assertThat(event.getDetails()).isEqualTo("The sketch was never started"),
                () -> assertThat(event.getActorUserId()).isEqualTo(customerId))),
            () -> assertThatThrownBy(() -> service.accept(customerId, commissionId, refundRequestId))
                .isInstanceOfSatisfying(AppException.class, exception -> assertThat(exception.getError())
                    .isEqualTo(AppErrorMessage.COMMISSION_REFUND_ACTION_NOT_ALLOWED))
        );
        verify(transferRepository, never()).saveAndFlush(any());
    }

    @Test
    void object_blank_reason_ko() {
        // given
        givenCommission();
        givenRefund(10, "REQUESTED");

        // when / then
        assertRejected(() -> service.object(customerId, commissionId, refundRequestId, "  "),
            AppErrorMessage.COMMISSION_INVALID);
    }

    @Test
    void admin_sets_final_percentage_after_objection_ok() {
        // given
        givenCommission();
        givenHeldFirstPayment(ProceedsStatus.HELD);
        var request = givenRefund(10, "REQUESTED");
        savedEvents.add(new CommissionEvent(commissionId, "REFUND_OBJECTED", null, null, refundRequestId, customerId,
            null, now(), "The sketch was never started"));

        // when
        var result = service.decide(staffId, refundRequestId, decision(100, " Nothing was delivered "));
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(audit.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(RefundStatus.APPROVED),
            () -> assertThat(result.getRefundPercent()).isEqualTo(100),
            () -> assertThat(request.getDecidedByStaffId()).isEqualTo(staffId),
            () -> assertThat(request.getDecisionReason()).isEqualTo("Nothing was delivered"),
            () -> assertThat(savedTransfers).singleElement().satisfies(transfer ->
                assertThat(transfer.getAmount()).isEqualByComparingTo("55002")),
            () -> assertThat(audit.getValue().getStaffId()).isEqualTo(staffId),
            () -> assertThat(audit.getValue().getActionType()).isEqualTo("COMMISSION_REFUND_DECIDED"),
            () -> assertThat(audit.getValue().getTargetType()).isEqualTo("REFUND_REQUEST"),
            () -> assertThat(audit.getValue().getTargetId()).isEqualTo(refundRequestId),
            () -> assertThat(audit.getValue().getReason()).isEqualTo("Nothing was delivered"),
            () -> assertThat(audit.getValue().getDetails()).isEqualTo("commissionId=3;percent=100;from=AWAITING_ADMIN"
                + ";status=APPROVED;amount=55002.0000;transferId=800"),
            () -> assertThat(savedEvents).filteredOn(event -> event.getEventType().equals("REFUND_DECIDED"))
                .singleElement().satisfies(event -> assertThat(event.getActorStaffId()).isEqualTo(staffId))
        );
    }

    @Test
    void admin_sets_percentage_without_artist_ok() {
        // given
        givenCommission();
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRefund(null, "REQUESTED");

        // when
        var result = service.decide(staffId, refundRequestId, decision(70, "The Artist did not answer"));

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(RefundStatus.APPROVED),
            () -> assertThat(result.getRefundPercent()).isEqualTo(70),
            () -> assertThat(savedTransfers).singleElement().satisfies(transfer ->
                assertThat(transfer.getAmount()).isEqualByComparingTo("38502"))
        );
    }

    @Test
    void admin_blank_reason_ko() {
        // given
        givenCommission();
        givenRefund(null, "REQUESTED");

        // when / then
        assertRejected(() -> service.decide(staffId, refundRequestId, decision(70, " ")),
            AppErrorMessage.COMMISSION_REFUND_REASON_INVALID);
        verify(auditService, never()).record(any());
    }

    @Test
    void decided_refund_cannot_change_ko() {
        // given
        givenCommission();
        givenHeldFirstPayment(ProceedsStatus.HELD);
        givenRefund(70, "APPROVED");

        // when / then
        assertRejected(() -> service.setPercent(artistUserId, commissionId, refundRequestId, 50),
            AppErrorMessage.REFUND_ALREADY_DECIDED);
        assertRejected(() -> service.accept(customerId, commissionId, refundRequestId),
            AppErrorMessage.REFUND_ALREADY_DECIDED);
        assertRejected(() -> service.decide(staffId, refundRequestId, decision(100, "Again")),
            AppErrorMessage.REFUND_ALREADY_DECIDED);
    }

    @Test
    void refund_not_found_ko() {
        // given
        givenCommission();
        var otherCommissionRefund = new RefundRequest(null, 4L, customerId, "Other", "REQUESTED", now(), null, null,
            null);
        otherCommissionRefund.setRefundRequestId(62L);
        when(refundRepository.findWithLockByRefundRequestId(62L)).thenReturn(Optional.of(otherCommissionRefund));
        when(refundRepository.findWithLockByRefundRequestId(99L)).thenReturn(Optional.empty());

        // when / then
        assertRejected(() -> service.setPercent(artistUserId, commissionId, 62L, 50),
            AppErrorMessage.REFUND_REQUEST_NOT_FOUND);
        assertRejected(() -> service.accept(customerId, commissionId, 99L), AppErrorMessage.REFUND_REQUEST_NOT_FOUND);
        assertRejected(() -> service.decide(staffId, 99L, decision(50, "Agreed")),
            AppErrorMessage.REFUND_REQUEST_NOT_FOUND);
    }

    @Test
    void non_participant_ko() {
        // given
        givenCommission();
        givenRefund(70, "REQUESTED");

        // when / then
        assertRejected(() -> service.accept(strangerId, commissionId, refundRequestId),
            AppErrorMessage.COMMISSION_NOT_FOUND);
        assertRejected(() -> service.list(strangerId, commissionId), AppErrorMessage.COMMISSION_NOT_FOUND);
    }

    @Test
    void held_share_not_held_ko() {
        // given
        givenCommission();
        givenHeldFirstPayment(ProceedsStatus.RESERVED);
        givenRefund(70, "REQUESTED");

        // when / then
        assertRejected(() -> service.accept(customerId, commissionId, refundRequestId),
            AppErrorMessage.COMMISSION_REFUND_NOT_ALLOWED);
        verify(proceedsService, never()).refundQuoteShare(anyLong(), any(), any());
        verify(ledgerService, never()).recordRefundApproved(any());
    }

    // Every rejection happens before anything is written.
    private void assertRejected(Runnable action, AppErrorMessage error) {
        assertThatThrownBy(action::run)
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(error);
        verify(refundRepository, never()).saveAndFlush(any());
        verify(transferRepository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    private Commission givenCommission() {
        var commission = new Commission(customerId, artistId, "Portrait of my cat", "CANCELLED", "Buyer", "Painter",
            now(), null, null);
        commission.setCommissionId(commissionId);
        when(repository.findWithLockByCommissionId(commissionId)).thenReturn(Optional.of(commission));
        when(repository.findById(commissionId)).thenReturn(Optional.of(commission));
        return commission;
    }

    private void givenHeldFirstPayment(ProceedsStatus proceedsStatus) {
        givenAcceptedQuote();
        givenFirstPayment("SUCCEEDED");
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

    private void givenAcceptedQuote() {
        var quote = new CommissionQuote(commissionId, 1, "Full-body portrait", "3 weeks",
            new BigDecimal("100001.0000"), new BigDecimal("10001.0000"), "VND", "ACCEPTED", now());
        quote.setQuoteId(quoteId);
        when(quoteRepository.findByCommissionIdAndStatus(commissionId, "ACCEPTED")).thenReturn(List.of(quote));
    }

    private void givenFirstPayment(String status) {
        var payment = new Payment(null, quoteId, new BigDecimal("55002.0000"), "VND", status, null, now(), null);
        payment.setPaymentId(paymentId);
        payment.setInstallment(1);
        when(paymentRepository.findByQuoteIdAndInstallment(quoteId, 1)).thenReturn(Optional.of(payment));
    }

    private RefundRequest givenRefund(Integer percent, String status) {
        var request = new RefundRequest(null, commissionId, customerId, "The deadline no longer works", status,
            now(), null, null, null);
        request.setRefundRequestId(refundRequestId);
        request.setRefundPercent(percent);
        when(refundRepository.findWithLockByRefundRequestId(refundRequestId)).thenReturn(Optional.of(request));
        when(refundRepository.findCommissionIdByRefundRequestId(refundRequestId)).thenReturn(Optional.of(commissionId));
        return request;
    }

    private CommissionRefundDecision decision(int percent, String reason) {
        return CommissionRefundDecision.builder()
            .withPercent(percent)
            .withReason(reason)
            .build();
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
