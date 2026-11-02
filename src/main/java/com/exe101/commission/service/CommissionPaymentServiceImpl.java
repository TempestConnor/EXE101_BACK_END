package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.commission.model.CancellationStatus;
import com.exe101.commission.model.CommissionStatus;
import com.exe101.commission.model.QuotePayment;
import com.exe101.commission.model.QuoteSettlement;
import com.exe101.commission.model.QuoteSplit;
import com.exe101.commission.model.QuoteStatus;
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
import com.exe101.order.model.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// The first 50% of an accepted Quote is paid before the sketch, the final 50% after the Customer approves the work
// (amendment §D.12–13). Both starting a payment and settling one lock the Commission row first and then the Payment,
// so a payment can never race the Quote or delivery actions on the same Commission, and two successes for one
// installment have exactly one winner (§31.3).
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CommissionPaymentServiceImpl implements CommissionPaymentService {
    private static int firstInstallment = 1;
    private static int finalInstallment = 2;
    private static String currency = "VND";
    private final CommissionRepository repository;
    private final CommissionQuoteRepository quoteRepository;
    private final CommissionEventRepository eventRepository;
    private final QuotePaymentRepository paymentRepository;
    private final CancellationRequestRepository cancellationRepository;
    private final ArtistService artistService;

    // Each installment's Payment is created with its first attempt, so Quotes accepted before Quote payments existed
    // need nothing else. The Commission is AWAITING_PAYMENT twice: before the first installment, and after approval,
    // when the first is SUCCEEDED and the final one is due. The final payment has no deadline (approved 2 November
    // 2026). A deactivated Artist's accepted Quote stays payable: it is an existing commitment (amendment §A.4).
    @Override
    public QuotePayment preparePayment(Long userId, Long commissionId, Long quoteId) {
        Commission commission = repository.findWithLockByCommissionId(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_NOT_FOUND));
        requireCustomer(userId, commission);
        CommissionQuote quote = quoteRepository.findByQuoteIdAndCommissionId(quoteId, commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.QUOTE_NOT_FOUND));

        // No new payment while a cancellation request is pending (§24.8); a DISPUTED Commission is not payable.
        if (cancellationRepository.existsByCommissionIdAndStatus(commissionId, CancellationStatus.PENDING.name())) {
            throw new AppException(AppErrorMessage.CANCELLATION_PENDING);
        }
        boolean payable = CommissionStatus.AWAITING_PAYMENT.name().equals(commission.getStatus())
            && QuoteStatus.ACCEPTED.name().equals(quote.getStatus());

        if (!payable) {
            throw new AppException(AppErrorMessage.QUOTE_NOT_PAYABLE);
        }

        Optional<Payment> first = paymentRepository.findByQuoteIdAndInstallment(quoteId, firstInstallment);
        boolean firstPaid = first.filter(found -> PaymentStatus.SUCCEEDED.name().equals(found.getStatus()))
            .isPresent();
        int installment = firstPaid ? finalInstallment : firstInstallment;
        Payment payment = (firstPaid ? paymentRepository.findByQuoteIdAndInstallment(quoteId, finalInstallment) : first)
            .orElseGet(() -> create(quote, installment));

        if (!PaymentStatus.PENDING.name().equals(payment.getStatus())) {
            throw new AppException(AppErrorMessage.QUOTE_NOT_PAYABLE);
        }

        return QuotePayment.builder()
            .withPaymentId(payment.getPaymentId())
            .withCommissionId(commissionId)
            .withQuoteId(quoteId)
            .withInstallment(installment)
            .withAmount(payment.getAmount())
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isQuotePayment(Long paymentId) {
        return paymentRepository.findQuoteIdByPaymentId(paymentId).isPresent();
    }

    // §12.6: only a confirmed success of the exact installment due changes the Commission. The first starts the work;
    // the final one marks the Quote PAID and completes the Commission (amendment §D.13), and the caller releases the
    // Quote's proceeds. Money that arrives when the installment can no longer be paid leaves the Commission as it is;
    // the caller records it for reconciliation.
    @Override
    public QuoteSettlement settlePayment(Long paymentId, BigDecimal amount, OffsetDateTime receivedAt) {
        Long quoteId = paymentRepository.findQuoteIdByPaymentId(paymentId)
            .orElseThrow(() -> new IllegalStateException("Payment has no Quote: " + paymentId));
        Long commissionId = quoteRepository.findCommissionIdByQuoteId(quoteId).orElseThrow();
        Commission commission = repository.findWithLockByCommissionId(commissionId).orElseThrow();
        Payment payment = paymentRepository.findWithLockByPaymentId(paymentId).orElseThrow();
        CommissionQuote quote = quoteRepository.findById(quoteId).orElseThrow();

        if (payment.getAmount().compareTo(amount) != 0) {
            return outcome(commissionId, PaymentSettlement.WRONG_AMOUNT);
        }

        if (PaymentStatus.SUCCEEDED.name().equals(payment.getStatus())) {
            return outcome(commissionId, PaymentSettlement.ALREADY_PAID);
        }

        boolean payable = CommissionStatus.AWAITING_PAYMENT.name().equals(commission.getStatus())
            && QuoteStatus.ACCEPTED.name().equals(quote.getStatus())
            && PaymentStatus.PENDING.name().equals(payment.getStatus());

        // A dispute only interrupts the Commission: its Payment stays PENDING, so the installment can be paid again
        // if the Admin lets the Commission continue.
        if (!payable) {
            if (!CommissionStatus.DISPUTED.name().equals(commission.getStatus())) {
                payment.setStatus(PaymentStatus.EXCEPTION.name());
                paymentRepository.saveAndFlush(payment);
            }

            return outcome(commissionId, PaymentSettlement.NOT_PAYABLE);
        }

        payment.setStatus(PaymentStatus.SUCCEEDED.name());
        payment.setSucceededAt(receivedAt);
        paymentRepository.saveAndFlush(payment);
        // The Quote stays ACCEPTED after the first installment: it is PAID only when the final one is paid too.
        boolean completes = payment.getInstallment() == finalInstallment;
        CommissionStatus to = completes ? CommissionStatus.COMPLETED : CommissionStatus.IN_PROGRESS;

        if (completes) {
            quote.setStatus(QuoteStatus.PAID.name());
            quote.setPaidAt(receivedAt);
            quoteRepository.saveAndFlush(quote);
            commission.setCompletedAt(receivedAt);
            closePendingCancellation(commission, receivedAt);
        }

        String from = commission.getStatus();
        commission.setStatus(to.name());
        repository.saveAndFlush(commission);
        eventRepository.saveAndFlush(new CommissionEvent(commissionId, "PAYMENT_CONFIRMED", from, to.name(),
            paymentId, commission.getCustomerId(), null, receivedAt, null));
        QuoteSplit split = QuoteSplit.of(quote.getPrice(), quote.getTaxAmount());
        log.info("[COMMISSION] - ACTION: settlePayment: commissionId: {}: quoteId: {}: installment: {}: outcome: {}:"
            + " amount: {}", commissionId, quoteId, payment.getInstallment(), PaymentSettlement.PAID, amount);

        return QuoteSettlement.builder()
            .withSettlement(PaymentSettlement.PAID)
            .withPaymentId(paymentId)
            .withCommissionId(commissionId)
            .withQuoteId(quoteId)
            .withArtistId(commission.getArtistId())
            .withInstallment(payment.getInstallment())
            .withAmount(payment.getAmount())
            .withProceeds(completes ? split.getFinalPrice() : split.getFirstPrice())
            .withTaxAmount(completes ? split.getFinalTax() : split.getFirstTax())
            .withPaidAt(receivedAt)
            .withCompleted(completes)
            .build();
    }

    // A final installment of 0 VND never reaches here: approval completes such a Commission at once.
    private Payment create(CommissionQuote quote, int installment) {
        QuoteSplit split = QuoteSplit.of(quote.getPrice(), quote.getTaxAmount());
        BigDecimal amount = installment == firstInstallment ? split.getFirstAmount() : split.getFinalAmount();

        if (amount.signum() <= 0) {
            throw new AppException(AppErrorMessage.QUOTE_NOT_PAYABLE);
        }

        Payment payment = new Payment(null, quote.getQuoteId(), amount, currency, PaymentStatus.PENDING.name(), null,
            now(), null);
        payment.setInstallment(installment);
        Payment saved = paymentRepository.saveAndFlush(payment);
        log.info("[COMMISSION] - ACTION: preparePayment: quoteId: {}: installment: {}: paymentId: {}: amount: {}",
            quote.getQuoteId(), installment, saved.getPaymentId(), saved.getAmount());

        return saved;
    }

    // A payment page opened before a cancellation was requested can still be paid. The final payment then completes
    // the Commission, and the request it overtook is closed as REJECTED, with the Customer, who paid, as actor.
    private void closePendingCancellation(Commission commission, OffsetDateTime closedAt) {
        cancellationRepository.findByCommissionIdAndStatus(commission.getCommissionId(),
            CancellationStatus.PENDING.name()).ifPresent(request -> {
                request.setStatus(CancellationStatus.REJECTED.name());
                request.setResolvedAt(closedAt);
                cancellationRepository.saveAndFlush(request);
                eventRepository.saveAndFlush(new CommissionEvent(commission.getCommissionId(), "CANCELLATION_CLOSED",
                    null, null, request.getCancellationId(), commission.getCustomerId(), null, closedAt,
                    "The final payment completed the Commission."));
            });
    }

    private QuoteSettlement outcome(Long commissionId, PaymentSettlement settlement) {
        log.warn("[COMMISSION] - ACTION: settlePayment: commissionId: {}: outcome: {}", commissionId, settlement);

        return QuoteSettlement.builder().withSettlement(settlement).withCommissionId(commissionId).build();
    }

    // Only the Customer pays; the Artist is refused, and to anyone else the Commission does not exist.
    private void requireCustomer(Long userId, Commission commission) {
        if (commission.getCustomerId() == userId) {
            return;
        }

        Artist artist = artistService.get(commission.getArtistId());

        if (artist.getUserId().equals(userId)) {
            throw new AppException(AppErrorMessage.ACCESS_DENIED);
        }

        throw new AppException(AppErrorMessage.COMMISSION_NOT_FOUND);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
