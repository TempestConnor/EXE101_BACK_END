package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.commission.model.QuoteSplit;
import com.exe101.commission.repository.CancellationRequestRepository;
import com.exe101.commission.repository.CommissionEventRepository;
import com.exe101.commission.repository.CommissionQuoteRepository;
import com.exe101.commission.repository.CommissionRepository;
import com.exe101.commission.repository.QuotePaymentRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.Commission;
import com.exe101.entity.CommissionEvent;
import com.exe101.entity.CommissionQuote;
import com.exe101.entity.Payment;
import com.exe101.order.model.PaymentSettlement;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommissionPaymentServiceTest {
    private static long customerId = 42L;
    private static long artistUserId = 50L;
    private static long strangerId = 77L;
    private static long artistId = 9L;
    private static long commissionId = 3L;
    private static long quoteId = 21L;
    private static long paymentId = 400L;
    private static long finalPaymentId = 401L;
    @Mock
    private CommissionRepository repository;
    @Mock
    private CommissionQuoteRepository quoteRepository;
    @Mock
    private CommissionEventRepository eventRepository;
    @Mock
    private QuotePaymentRepository paymentRepository;
    @Mock
    private CancellationRequestRepository cancellationRepository;
    @Mock
    private ArtistService artistService;
    @Mock
    private CommissionRefundService refundService;
    @InjectMocks
    private CommissionPaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        when(artistService.get(artistId)).thenReturn(artist("ACTIVE"));
        when(paymentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);

            if (payment.getPaymentId() == null) {
                payment.setPaymentId(paymentId);
            }

            return payment;
        });
    }

    @Test
    void start_payment_creates_first_installment_ok() {
        // given
        givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "0.0000");

        // when
        var result = service.preparePayment(customerId, commissionId, quoteId);
        var saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(saved.capture());

        // then
        assertAll(
            () -> assertThat(result.getPaymentId()).isEqualTo(paymentId),
            () -> assertThat(result.getInstallment()).isEqualTo(1),
            () -> assertThat(result.getAmount()).isEqualTo(new BigDecimal("50001.0000")),
            () -> assertThat(saved.getValue().getQuoteId()).isEqualTo(quoteId),
            () -> assertThat(saved.getValue().getOrderId()).isNull(),
            () -> assertThat(saved.getValue().getInstallment()).isEqualTo(1),
            () -> assertThat(saved.getValue().getAmount()).isEqualTo(new BigDecimal("50001.0000")),
            () -> assertThat(saved.getValue().getCurrencyCode()).isEqualTo("VND"),
            () -> assertThat(saved.getValue().getStatus()).isEqualTo("PENDING"),
            () -> assertThat(saved.getValue().getSucceededAt()).isNull()
        );
    }

    @Test
    void start_payment_reuses_pending_payment_ok() {
        // given
        givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "0.0000");
        givenPayment("PENDING", "50001.0000");

        // when
        var result = service.preparePayment(customerId, commissionId, quoteId);

        // then
        assertAll(
            () -> assertThat(result.getPaymentId()).isEqualTo(paymentId),
            () -> assertThat(result.getAmount()).isEqualTo(new BigDecimal("50001.0000"))
        );
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void start_payment_by_artist_ko() {
        // given
        givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "0.0000");

        // when / then
        assertRejected(() -> service.preparePayment(artistUserId, commissionId, quoteId),
            AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void start_payment_by_stranger_ko() {
        // given
        givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "0.0000");

        // when / then
        assertRejected(() -> service.preparePayment(strangerId, commissionId, quoteId),
            AppErrorMessage.COMMISSION_NOT_FOUND);
    }

    @Test
    void start_payment_unknown_quote_ko() {
        // given
        givenCommission("AWAITING_PAYMENT");
        when(quoteRepository.findByQuoteIdAndCommissionId(quoteId, commissionId)).thenReturn(Optional.empty());

        // when / then
        assertRejected(() -> service.preparePayment(customerId, commissionId, quoteId),
            AppErrorMessage.QUOTE_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "QUOTED", "IN_PROGRESS", "DECLINED", "CANCELLED"})
    void start_payment_not_awaiting_payment_ko(String status) {
        // given
        givenCommission(status);
        givenQuote("ACCEPTED", "100001.0000", "0.0000");

        // when / then
        assertRejected(() -> service.preparePayment(customerId, commissionId, quoteId),
            AppErrorMessage.QUOTE_NOT_PAYABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OFFERED", "DECLINED", "WITHDRAWN"})
    void start_payment_quote_not_accepted_ko(String status) {
        // given
        givenCommission("AWAITING_PAYMENT");
        givenQuote(status, "100001.0000", "0.0000");

        // when / then
        assertRejected(() -> service.preparePayment(customerId, commissionId, quoteId),
            AppErrorMessage.QUOTE_NOT_PAYABLE);
    }

    @Test
    void start_payment_deactivated_artist_ok() {
        // given
        // An accepted Quote is an existing commitment and stays payable (amendment §A.4).
        when(artistService.get(artistId)).thenReturn(artist("SUSPENDED"));
        when(artistService.lockForCommission(artistId)).thenReturn(artist("SUSPENDED"));
        givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "0.0000");

        // when
        var result = service.preparePayment(customerId, commissionId, quoteId);

        // then
        assertThat(result.getAmount()).isEqualTo(new BigDecimal("50001.0000"));
    }

    @Test
    void installment_split_rounds_first_half_up_ok() {
        // given
        var price = new BigDecimal("100001.0000");
        var tax = new BigDecimal("10001.0000");

        // when
        var split = QuoteSplit.of(price, tax);

        // then
        assertAll(
            () -> assertThat(split.getFirstPrice()).isEqualTo(new BigDecimal("50001.0000")),
            () -> assertThat(split.getFirstTax()).isEqualTo(new BigDecimal("5001.0000")),
            () -> assertThat(split.getFinalPrice()).isEqualTo(new BigDecimal("50000.0000")),
            () -> assertThat(split.getFinalTax()).isEqualTo(new BigDecimal("5000.0000")),
            () -> assertThat(split.getFirstAmount()).isEqualTo(new BigDecimal("55002.0000")),
            () -> assertThat(split.getFinalAmount()).isEqualTo(new BigDecimal("55000.0000")),
            () -> assertThat(split.getFirstAmount().add(split.getFinalAmount())).isEqualTo(price.add(tax))
        );
    }

    @Test
    void installment_split_even_amount_ok() {
        // given
        var price = new BigDecimal("200000.0000");
        var tax = new BigDecimal("20000.0000");

        // when
        var split = QuoteSplit.of(price, tax);

        // then
        assertAll(
            () -> assertThat(split.getFirstAmount()).isEqualTo(new BigDecimal("110000.0000")),
            () -> assertThat(split.getFinalAmount()).isEqualTo(new BigDecimal("110000.0000")),
            () -> assertThat(split.getFirstPrice()).isEqualTo(split.getFinalPrice()),
            () -> assertThat(split.getFirstTax()).isEqualTo(split.getFinalTax())
        );
    }

    @Test
    void settle_first_payment_starts_work_ok() {
        // given
        var commission = givenCommission("AWAITING_PAYMENT");
        var quote = givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        var payment = givenSettling("PENDING", "55002.0000");
        var receivedAt = now();

        // when
        var result = service.settlePayment(paymentId, new BigDecimal("55002"), receivedAt);
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getSettlement()).isEqualTo(PaymentSettlement.PAID),
            () -> assertThat(result.getPaymentId()).isEqualTo(paymentId),
            () -> assertThat(result.getQuoteId()).isEqualTo(quoteId),
            () -> assertThat(result.getArtistId()).isEqualTo(artistId),
            () -> assertThat(result.getInstallment()).isEqualTo(1),
            () -> assertThat(result.getAmount()).isEqualTo(new BigDecimal("55002.0000")),
            () -> assertThat(result.getProceeds()).isEqualTo(new BigDecimal("50001.0000")),
            () -> assertThat(result.getTaxAmount()).isEqualTo(new BigDecimal("5001.0000")),
            () -> assertThat(result.getPaidAt()).isEqualTo(receivedAt),
            () -> assertThat(payment.getStatus()).isEqualTo("SUCCEEDED"),
            () -> assertThat(payment.getSucceededAt()).isEqualTo(receivedAt),
            () -> assertThat(commission.getStatus()).isEqualTo("IN_PROGRESS"),
            () -> assertThat(quote.getStatus()).isEqualTo("ACCEPTED"),
            () -> assertThat(quote.getPaidAt()).isNull(),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("PAYMENT_CONFIRMED"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("AWAITING_PAYMENT"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("IN_PROGRESS"),
            () -> assertThat(event.getValue().getRelatedId()).isEqualTo(paymentId),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(customerId)
        );
    }

    @Test
    void settle_wrong_amount_ko() {
        // given
        var commission = givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        var payment = givenSettling("PENDING", "55002.0000");

        // when
        var result = service.settlePayment(paymentId, new BigDecimal("55000"), now());

        // then
        assertAll(
            () -> assertThat(result.getSettlement()).isEqualTo(PaymentSettlement.WRONG_AMOUNT),
            () -> assertThat(payment.getStatus()).isEqualTo("PENDING"),
            () -> assertThat(commission.getStatus()).isEqualTo("AWAITING_PAYMENT")
        );
        verify(paymentRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void settle_already_paid_ko() {
        // given
        var commission = givenCommission("IN_PROGRESS");
        givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        var payment = givenSettling("SUCCEEDED", "55002.0000");
        payment.setSucceededAt(now().minusMinutes(5));

        // when
        var result = service.settlePayment(paymentId, new BigDecimal("55002"), now());

        // then
        assertAll(
            () -> assertThat(result.getSettlement()).isEqualTo(PaymentSettlement.ALREADY_PAID),
            () -> assertThat(payment.getStatus()).isEqualTo("SUCCEEDED"),
            () -> assertThat(commission.getStatus()).isEqualTo("IN_PROGRESS")
        );
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void settle_not_payable_ko() {
        // given
        // The Commission left AWAITING_PAYMENT while the buyer was on the VNPay page.
        var commission = givenCommission("CANCELLED");
        givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        var payment = givenSettling("PENDING", "55002.0000");

        // when
        var result = service.settlePayment(paymentId, new BigDecimal("55002"), now());

        // then
        assertAll(
            () -> assertThat(result.getSettlement()).isEqualTo(PaymentSettlement.NOT_PAYABLE),
            () -> assertThat(payment.getStatus()).isEqualTo("EXCEPTION"),
            () -> assertThat(payment.getSucceededAt()).isNull(),
            () -> assertThat(commission.getStatus()).isEqualTo("CANCELLED")
        );
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void payment_attempt_after_approval_never_repays_first_ok() {
        // given
        // Approval returns the Commission to AWAITING_PAYMENT for the final 50% (F18), with the first one paid.
        givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        var first = givenPayment("SUCCEEDED", "55002.0000");
        first.setSucceededAt(now().minusDays(3));

        // when
        var result = service.preparePayment(customerId, commissionId, quoteId);
        var saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(saved.capture());

        // then
        assertAll(
            () -> assertThat(result.getInstallment()).isEqualTo(2),
            () -> assertThat(saved.getValue().getInstallment()).isEqualTo(2),
            () -> assertThat(first.getStatus()).isEqualTo("SUCCEEDED")
        );
    }

    @Test
    void final_payment_prepare_ok() {
        // given
        givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        givenPayment("SUCCEEDED", "55002.0000");

        // when
        var result = service.preparePayment(customerId, commissionId, quoteId);
        var saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(saved.capture());

        // then
        assertAll(
            () -> assertThat(result.getInstallment()).isEqualTo(2),
            () -> assertThat(result.getAmount()).isEqualTo(new BigDecimal("55000.0000")),
            () -> assertThat(saved.getValue().getQuoteId()).isEqualTo(quoteId),
            () -> assertThat(saved.getValue().getOrderId()).isNull(),
            () -> assertThat(saved.getValue().getInstallment()).isEqualTo(2),
            () -> assertThat(saved.getValue().getAmount()).isEqualTo(new BigDecimal("55000.0000")),
            () -> assertThat(saved.getValue().getStatus()).isEqualTo("PENDING"),
            () -> assertThat(saved.getValue().getSucceededAt()).isNull()
        );
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void final_payment_prepare_reuses_pending_ok() {
        // given
        givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        givenPayment("SUCCEEDED", "55002.0000");
        givenFinalPayment("PENDING", "55000.0000");

        // when
        var result = service.preparePayment(customerId, commissionId, quoteId);

        // then
        assertAll(
            () -> assertThat(result.getPaymentId()).isEqualTo(finalPaymentId),
            () -> assertThat(result.getInstallment()).isEqualTo(2),
            () -> assertThat(result.getAmount()).isEqualTo(new BigDecimal("55000.0000"))
        );
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void final_payment_amount_remainder_ok() {
        // given
        givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        givenPayment("SUCCEEDED", "55002.0000");
        givenFinalSettling("PENDING", "55000.0000");

        // when
        var result = service.settlePayment(finalPaymentId, new BigDecimal("55000"), now());

        // then
        assertAll(
            () -> assertThat(result.getAmount()).isEqualTo(new BigDecimal("55000.0000")),
            () -> assertThat(result.getProceeds()).isEqualTo(new BigDecimal("50000.0000")),
            () -> assertThat(result.getTaxAmount()).isEqualTo(new BigDecimal("5000.0000")),
            () -> assertThat(result.getProceeds().add(result.getTaxAmount())).isEqualTo(result.getAmount())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "SUBMITTED_FOR_REVIEW", "REVISION_REQUESTED"})
    void final_payment_prepare_before_approval_ko(String status) {
        // given
        givenCommission(status);
        givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        givenPayment("SUCCEEDED", "55002.0000");

        // when / then
        assertRejected(() -> service.preparePayment(customerId, commissionId, quoteId),
            AppErrorMessage.QUOTE_NOT_PAYABLE);
    }

    @Test
    void final_payment_prepare_completed_ko() {
        // given
        givenCommission("COMPLETED");
        givenQuote("PAID", "100001.0000", "10001.0000");
        givenPayment("SUCCEEDED", "55002.0000");
        givenFinalPayment("SUCCEEDED", "55000.0000");

        // when / then
        assertRejected(() -> service.preparePayment(customerId, commissionId, quoteId),
            AppErrorMessage.QUOTE_NOT_PAYABLE);
    }

    @Test
    void final_payment_prepare_artist_ko() {
        // given
        givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        givenPayment("SUCCEEDED", "55002.0000");

        // when / then
        assertRejected(() -> service.preparePayment(artistUserId, commissionId, quoteId),
            AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void final_payment_success_completes_ok() {
        // given
        var commission = givenCommission("AWAITING_PAYMENT");
        var quote = givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        givenPayment("SUCCEEDED", "55002.0000");
        var payment = givenFinalSettling("PENDING", "55000.0000");
        var receivedAt = now();

        // when
        var result = service.settlePayment(finalPaymentId, new BigDecimal("55000"), receivedAt);
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getSettlement()).isEqualTo(PaymentSettlement.PAID),
            () -> assertThat(result.getPaymentId()).isEqualTo(finalPaymentId),
            () -> assertThat(result.getInstallment()).isEqualTo(2),
            () -> assertThat(result.isCompleted()).isTrue(),
            () -> assertThat(result.getArtistId()).isEqualTo(artistId),
            () -> assertThat(result.getPaidAt()).isEqualTo(receivedAt),
            () -> assertThat(payment.getStatus()).isEqualTo("SUCCEEDED"),
            () -> assertThat(payment.getSucceededAt()).isEqualTo(receivedAt),
            () -> assertThat(quote.getStatus()).isEqualTo("PAID"),
            () -> assertThat(quote.getPaidAt()).isEqualTo(receivedAt),
            () -> assertThat(commission.getStatus()).isEqualTo("COMPLETED"),
            () -> assertThat(commission.getCompletedAt()).isEqualTo(receivedAt),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("PAYMENT_CONFIRMED"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("AWAITING_PAYMENT"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("COMPLETED"),
            () -> assertThat(event.getValue().getRelatedId()).isEqualTo(finalPaymentId),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(customerId)
        );
        verify(quoteRepository).saveAndFlush(quote);
    }

    @Test
    void final_payment_closes_pending_offer_ok() {
        // given
        // The Artist asked to cancel with a refund offer after the Customer opened the final payment page.
        givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        givenPayment("SUCCEEDED", "55002.0000");
        givenFinalSettling("PENDING", "55000.0000");
        var request = new com.exe101.entity.CancellationRequest(commissionId, artistUserId, "Leaving the team",
            "PENDING", now());
        request.setCancellationId(81L);
        when(cancellationRepository.findByCommissionIdAndStatus(commissionId, "PENDING"))
            .thenReturn(Optional.of(request));
        var receivedAt = now();

        // when
        var result = service.settlePayment(finalPaymentId, new BigDecimal("55000"), receivedAt);

        // then
        assertAll(
            () -> assertThat(result.isCompleted()).isTrue(),
            () -> assertThat(request.getStatus()).isEqualTo("REJECTED"),
            () -> assertThat(request.getResolvedAt()).isEqualTo(receivedAt)
        );
        verify(refundService).closeOffer(commissionId, "The final payment completed the Commission.", receivedAt);
    }

    @Test
    void final_payment_success_long_after_approval_ok() {
        // given
        // There is no deadline for the final payment (approved 2 November 2026).
        var commission = givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        givenPayment("SUCCEEDED", "55002.0000").setSucceededAt(now().minusDays(60));
        var payment = givenFinalSettling("PENDING", "55000.0000");
        payment.setCreatedAt(now().minusDays(45));

        // when
        var result = service.settlePayment(finalPaymentId, new BigDecimal("55000"), now());

        // then
        assertAll(
            () -> assertThat(result.getSettlement()).isEqualTo(PaymentSettlement.PAID),
            () -> assertThat(commission.getStatus()).isEqualTo("COMPLETED")
        );
    }

    @Test
    void final_payment_wrong_amount_ko() {
        // given
        var commission = givenCommission("AWAITING_PAYMENT");
        var quote = givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        givenPayment("SUCCEEDED", "55002.0000");
        var payment = givenFinalSettling("PENDING", "55000.0000");

        // when
        var result = service.settlePayment(finalPaymentId, new BigDecimal("55002"), now());

        // then
        assertAll(
            () -> assertThat(result.getSettlement()).isEqualTo(PaymentSettlement.WRONG_AMOUNT),
            () -> assertThat(payment.getStatus()).isEqualTo("PENDING"),
            () -> assertThat(quote.getStatus()).isEqualTo("ACCEPTED"),
            () -> assertThat(commission.getStatus()).isEqualTo("AWAITING_PAYMENT")
        );
        verify(paymentRepository, never()).saveAndFlush(any());
        verify(quoteRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void final_payment_second_success_ko() {
        // given
        var commission = givenCommission("COMPLETED");
        var quote = givenQuote("PAID", "100001.0000", "10001.0000");
        givenPayment("SUCCEEDED", "55002.0000");
        var payment = givenFinalSettling("SUCCEEDED", "55000.0000");

        // when
        var result = service.settlePayment(finalPaymentId, new BigDecimal("55000"), now());

        // then
        assertAll(
            () -> assertThat(result.getSettlement()).isEqualTo(PaymentSettlement.ALREADY_PAID),
            () -> assertThat(result.isCompleted()).isFalse(),
            () -> assertThat(payment.getStatus()).isEqualTo("SUCCEEDED"),
            () -> assertThat(quote.getStatus()).isEqualTo("PAID"),
            () -> assertThat(commission.getStatus()).isEqualTo("COMPLETED")
        );
        verify(paymentRepository, never()).saveAndFlush(any());
        verify(quoteRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void final_payment_success_not_payable_ko() {
        // given
        var commission = givenCommission("CANCELLED");
        var quote = givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        givenPayment("SUCCEEDED", "55002.0000");
        var payment = givenFinalSettling("PENDING", "55000.0000");

        // when
        var result = service.settlePayment(finalPaymentId, new BigDecimal("55000"), now());

        // then
        assertAll(
            () -> assertThat(result.getSettlement()).isEqualTo(PaymentSettlement.NOT_PAYABLE),
            () -> assertThat(payment.getStatus()).isEqualTo("EXCEPTION"),
            () -> assertThat(payment.getSucceededAt()).isNull(),
            () -> assertThat(quote.getStatus()).isEqualTo("ACCEPTED"),
            () -> assertThat(commission.getStatus()).isEqualTo("CANCELLED")
        );
        verify(quoteRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void first_payment_success_after_approval_ko() {
        // given
        var commission = givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        var payment = givenSettling("SUCCEEDED", "55002.0000");
        payment.setSucceededAt(now().minusDays(3));

        // when
        var result = service.settlePayment(paymentId, new BigDecimal("55002"), now());

        // then
        assertAll(
            () -> assertThat(result.getSettlement()).isEqualTo(PaymentSettlement.ALREADY_PAID),
            () -> assertThat(payment.getStatus()).isEqualTo("SUCCEEDED"),
            () -> assertThat(commission.getStatus()).isEqualTo("AWAITING_PAYMENT")
        );
        verify(paymentRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void pending_cancellation_blocks_payment_attempt_ko() {
        // given
        givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        givenPayment("SUCCEEDED", "55002.0000");
        when(cancellationRepository.existsByCommissionIdAndStatus(commissionId, "PENDING")).thenReturn(true);

        // when / then
        assertRejected(() -> service.preparePayment(customerId, commissionId, quoteId),
            AppErrorMessage.CANCELLATION_PENDING);
    }

    @Test
    void disputed_commission_refuses_payment_attempt_ko() {
        // given
        givenCommission("DISPUTED");
        givenQuote("ACCEPTED", "100001.0000", "10001.0000");

        // when / then
        assertRejected(() -> service.preparePayment(customerId, commissionId, quoteId),
            AppErrorMessage.QUOTE_NOT_PAYABLE);
    }

    @Test
    void quote_payment_success_on_disputed_commission_ko() {
        // given
        // A VNPay page opened before the dispute is paid while the Commission is DISPUTED.
        var commission = givenCommission("DISPUTED");
        var quote = givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        givenPayment("SUCCEEDED", "55002.0000");
        var payment = givenFinalSettling("PENDING", "55000.0000");

        // when
        var result = service.settlePayment(finalPaymentId, new BigDecimal("55000"), now());

        // then
        assertAll(
            () -> assertThat(result.getSettlement()).isEqualTo(PaymentSettlement.NOT_PAYABLE),
            () -> assertThat(payment.getStatus()).isEqualTo("PENDING"),
            () -> assertThat(payment.getSucceededAt()).isNull(),
            () -> assertThat(quote.getStatus()).isEqualTo("ACCEPTED"),
            () -> assertThat(commission.getStatus()).isEqualTo("DISPUTED")
        );
        verify(paymentRepository, never()).saveAndFlush(any());
        verify(quoteRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void quote_payment_success_on_cancelled_commission_ko() {
        // given
        // Cancelling before the first payment cancelled the Payment; the page was still paid.
        var commission = givenCommission("CANCELLED");
        var quote = givenQuote("ACCEPTED", "100001.0000", "10001.0000");
        var payment = givenSettling("CANCELLED", "55002.0000");

        // when
        var result = service.settlePayment(paymentId, new BigDecimal("55002"), now());

        // then
        assertAll(
            () -> assertThat(result.getSettlement()).isEqualTo(PaymentSettlement.NOT_PAYABLE),
            () -> assertThat(payment.getStatus()).isEqualTo("EXCEPTION"),
            () -> assertThat(payment.getSucceededAt()).isNull(),
            () -> assertThat(quote.getStatus()).isEqualTo("ACCEPTED"),
            () -> assertThat(commission.getStatus()).isEqualTo("CANCELLED")
        );
        verify(quoteRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    // Every rejection happens before anything is written.
    private void assertRejected(Runnable action, AppErrorMessage error) {
        assertThatThrownBy(action::run)
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(error);
        verify(paymentRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    private Commission givenCommission(String status) {
        var commission = new Commission(customerId, artistId, "Portrait of my cat", status, "Buyer", "Painter",
            now(), null, null);
        commission.setCommissionId(commissionId);
        when(repository.findWithLockByCommissionId(commissionId)).thenReturn(Optional.of(commission));
        when(repository.saveAndFlush(commission)).thenReturn(commission);
        return commission;
    }

    private CommissionQuote givenQuote(String status, String price, String tax) {
        var quote = new CommissionQuote(commissionId, 1, "Full-body portrait", "3 weeks", new BigDecimal(price),
            new BigDecimal(tax), "VND", status, now());
        quote.setQuoteId(quoteId);

        if ("ACCEPTED".equals(status)) {
            quote.setAcceptedAt(now());
        }

        when(quoteRepository.findByQuoteIdAndCommissionId(quoteId, commissionId)).thenReturn(Optional.of(quote));
        when(quoteRepository.findById(quoteId)).thenReturn(Optional.of(quote));
        when(quoteRepository.findCommissionIdByQuoteId(quoteId)).thenReturn(Optional.of(commissionId));
        return quote;
    }

    private Payment givenPayment(String status, String amount) {
        var payment = new Payment(null, quoteId, new BigDecimal(amount), "VND", status, null, now(), null);
        payment.setPaymentId(paymentId);
        payment.setInstallment(1);
        when(paymentRepository.findByQuoteIdAndInstallment(quoteId, 1)).thenReturn(Optional.of(payment));
        return payment;
    }

    private Payment givenFinalPayment(String status, String amount) {
        var payment = new Payment(null, quoteId, new BigDecimal(amount), "VND", status, null, now(), null);
        payment.setPaymentId(finalPaymentId);
        payment.setInstallment(2);
        when(paymentRepository.findByQuoteIdAndInstallment(quoteId, 2)).thenReturn(Optional.of(payment));
        return payment;
    }

    private Payment givenFinalSettling(String status, String amount) {
        var payment = givenFinalPayment(status, amount);
        when(paymentRepository.findQuoteIdByPaymentId(finalPaymentId)).thenReturn(Optional.of(quoteId));
        when(paymentRepository.findWithLockByPaymentId(finalPaymentId)).thenReturn(Optional.of(payment));
        return payment;
    }

    private Payment givenSettling(String status, String amount) {
        var payment = givenPayment(status, amount);
        when(paymentRepository.findQuoteIdByPaymentId(paymentId)).thenReturn(Optional.of(quoteId));
        when(paymentRepository.findWithLockByPaymentId(paymentId)).thenReturn(Optional.of(payment));
        return payment;
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
