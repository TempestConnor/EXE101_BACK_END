package com.exe101.commission.service;

import com.exe101.commission.model.CommissionStatus;
import com.exe101.commission.model.QuoteStatus;
import com.exe101.commission.repository.CommissionEventRepository;
import com.exe101.commission.repository.CommissionQuoteRepository;
import com.exe101.commission.repository.CommissionRepository;
import com.exe101.commission.repository.QuotePaymentRepository;
import com.exe101.entity.Commission;
import com.exe101.entity.CommissionEvent;
import com.exe101.entity.CommissionQuote;
import com.exe101.entity.Payment;
import com.exe101.order.model.PaymentStatus;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Cancelling ends the Commission and moves no money (§24.11–12): held installments stay HELD until a Commission
// refund decides them (F20, approved 2 November 2026). A Payment still awaited becomes CANCELLED, but its provider
// attempts stay open, so a success that lands later is recorded as an exception and booked to UNAPPLIED.
// Runs inside the caller's transaction, with the Commission row already locked.
@Component
@RequiredArgsConstructor
public class CommissionCancellations {
    private final CommissionRepository repository;
    private final CommissionQuoteRepository quoteRepository;
    private final QuotePaymentRepository paymentRepository;
    private final CommissionEventRepository eventRepository;

    public void cancel(
        Commission commission, String eventType, Long relatedId, Long actorUserId, Long actorStaffId, String details,
        OffsetDateTime cancelledAt
    ) {
        Long commissionId = commission.getCommissionId();

        // An offer still open is withdrawn with the Commission, as when the Artist declines a request.
        for (CommissionQuote quote : quoteRepository.findByCommissionIdAndStatus(commissionId,
            QuoteStatus.OFFERED.name())) {
            quote.setStatus(QuoteStatus.WITHDRAWN.name());
            quoteRepository.saveAndFlush(quote);
            eventRepository.saveAndFlush(new CommissionEvent(commissionId, "QUOTE_WITHDRAWN", null, null,
                quote.getQuoteId(), actorUserId, actorStaffId, cancelledAt, null));
        }

        for (CommissionQuote quote : quoteRepository.findByCommissionIdAndStatus(commissionId,
            QuoteStatus.ACCEPTED.name())) {
            for (Payment payment : paymentRepository.findByQuoteIdAndStatus(quote.getQuoteId(),
                PaymentStatus.PENDING.name())) {
                payment.setStatus(PaymentStatus.CANCELLED.name());
                paymentRepository.saveAndFlush(payment);
            }
        }

        String from = commission.getStatus();
        commission.setStatus(CommissionStatus.CANCELLED.name());
        repository.saveAndFlush(commission);
        eventRepository.saveAndFlush(new CommissionEvent(commissionId, eventType, from,
            CommissionStatus.CANCELLED.name(), relatedId, actorUserId, actorStaffId, cancelledAt, details));
    }
}
