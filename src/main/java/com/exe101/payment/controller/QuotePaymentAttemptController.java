package com.exe101.payment.controller;

import com.exe101.payment.dto.QuotePaymentAttemptDTO;
import com.exe101.payment.mapper.PaymentMapper;
import com.exe101.payment.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Payment attempts for an accepted Quote; the Customer pays the installment due on the returned VNPay page.
@RestController
@RequestMapping("/commissions/{commissionId}/quotes/{quoteId}/payment-attempts")
@RequiredArgsConstructor
public class QuotePaymentAttemptController {
    private final PaymentService paymentService;
    private final PaymentMapper mapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuotePaymentAttemptDTO start(
        @PathVariable Long commissionId, @PathVariable Long quoteId, @AuthenticationPrincipal Long userId,
        HttpServletRequest request
    ) {
        var start = paymentService.startQuote(userId, commissionId, quoteId, request.getRemoteAddr());
        var response = mapper.toQuoteDto(start);

        return response;
    }
}
