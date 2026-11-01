package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.commission.model.CommissionStatus;
import com.exe101.commission.model.QuotePayment;
import com.exe101.commission.model.QuoteSettlement;
import com.exe101.commission.model.QuoteSplit;
import com.exe101.commission.model.QuoteStatus;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// The first 50% of an accepted Quote is paid before the sketch (amendment §D.12–13). Both starting a payment and
// settling one lock the Commission row first and then the Payment, so a payment can never race the Quote actions on
// the same Commission, and two successes for one installment have exactly one winner (§31.3).
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CommissionPaymentServiceImpl implements CommissionPaymentService {
    private static int firstInstallment = 1;
    private static String currency = "VND";
    private final CommissionRepository repository;
    private final CommissionQuoteRepository quoteRepository;
    private final CommissionEventRepository eventRepository;
    private final QuotePaymentRepository paymentRepository;
    private final ArtistService artistService;

    // The Payment is created with the first attempt, so Quotes accepted before Quote payments existed need nothing
    // else. A deactivated Artist's accepted Quote stays payable: it is an existing commitment (amendment §A.4).
    @Override
    public QuotePayment preparePayment(Long userId, Long commissionId, Long quoteId) {
        Commission commission = repository.findWithLockByCommissionId(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_NOT_FOUND));
        requireCustomer(userId, commission);
        CommissionQuote quote = quoteRepository.findByQuoteIdAndCommissionId(quoteId, commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.QUOTE_NOT_FOUND));
        boolean payable = CommissionStatus.AWAITING_PAYMENT.name().equals(commission.getStatus())
            && QuoteStatus.ACCEPTED.name().equals(quote.getStatus());

        if (!payable) {
            throw new AppException(AppErrorMessage.QUOTE_NOT_PAYABLE);
        }

        Payment payment = paymentRepository.findByQuoteIdAndInstallment(quoteId, firstInstallment)
            .orElseGet(() -> create(quote));

        if (!PaymentStatus.PENDING.name().equals(payment.getStatus())) {
            throw new AppException(AppErrorMessage.QUOTE_NOT_PAYABLE);
        }

        return QuotePayment.builder()
            .withPaymentId(payment.getPaymentId())
            .withCommissionId(commissionId)
            .withQuoteId(quoteId)
            .withInstallment(firstInstallment)
            .withAmount(payment.getAmount())
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isQuotePayment(Long paymentId) {
        return paymentRepository.findQuoteIdByPaymentId(paymentId).isPresent();
    }

    // §12.6: only a confirmed success of the exact first installment starts the work. Money that arrives when the
    // Quote can no longer be paid leaves the Commission as it is; the caller records it for reconciliation.
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

        if (!payable) {
            payment.setStatus(PaymentStatus.EXCEPTION.name());
            paymentRepository.saveAndFlush(payment);

            return outcome(commissionId, PaymentSettlement.NOT_PAYABLE);
        }

        payment.setStatus(PaymentStatus.SUCCEEDED.name());
        payment.setSucceededAt(receivedAt);
        paymentRepository.saveAndFlush(payment);
        // The Quote stays ACCEPTED: it is PAID only when the final installment is paid too.
        String from = commission.getStatus();
        commission.setStatus(CommissionStatus.IN_PROGRESS.name());
        repository.saveAndFlush(commission);
        eventRepository.saveAndFlush(new CommissionEvent(commissionId, "PAYMENT_CONFIRMED", from,
            CommissionStatus.IN_PROGRESS.name(), paymentId, commission.getCustomerId(), null, receivedAt, null));
        QuoteSplit split = QuoteSplit.of(quote.getPrice(), quote.getTaxAmount());
        log.info("[COMMISSION] - ACTION: settlePayment: commissionId: {}: quoteId: {}: outcome: {}: amount: {}",
            commissionId, quoteId, PaymentSettlement.PAID, amount);

        return QuoteSettlement.builder()
            .withSettlement(PaymentSettlement.PAID)
            .withPaymentId(paymentId)
            .withCommissionId(commissionId)
            .withQuoteId(quoteId)
            .withArtistId(commission.getArtistId())
            .withInstallment(firstInstallment)
            .withAmount(payment.getAmount())
            .withProceeds(split.getFirstPrice())
            .withTaxAmount(split.getFirstTax())
            .withPaidAt(receivedAt)
            .build();
    }

    private Payment create(CommissionQuote quote) {
        QuoteSplit split = QuoteSplit.of(quote.getPrice(), quote.getTaxAmount());
        Payment payment = new Payment(null, quote.getQuoteId(), split.getFirstAmount(), currency,
            PaymentStatus.PENDING.name(), null, now(), null);
        payment.setInstallment(firstInstallment);
        Payment saved = paymentRepository.saveAndFlush(payment);
        log.info("[COMMISSION] - ACTION: preparePayment: quoteId: {}: paymentId: {}: amount: {}", quote.getQuoteId(),
            saved.getPaymentId(), saved.getAmount());

        return saved;
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
