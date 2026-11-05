package com.exe101.commission.service;

import com.exe101.commission.model.QuotePayment;
import com.exe101.commission.model.QuoteSettlement;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface CommissionPaymentService {
    QuotePayment preparePayment(Long userId, Long commissionId, Long quoteId);

    boolean isQuotePayment(Long paymentId);

    Optional<QuotePayment> findOwnPayment(Long userId, Long paymentId);

    QuoteSettlement settlePayment(Long paymentId, BigDecimal amount, OffsetDateTime receivedAt);
}
