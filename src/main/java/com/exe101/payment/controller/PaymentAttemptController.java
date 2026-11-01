package com.exe101.payment.controller;

import com.exe101.payment.dto.PaymentAttemptDTO;
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

// Payment attempts belong to an Order, so they live under its path; the buyer pays on the returned VNPay page.
@RestController
@RequestMapping("/orders/{orderId}/payment-attempts")
@RequiredArgsConstructor
public class PaymentAttemptController {
    private final PaymentService paymentService;
    private final PaymentMapper mapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentAttemptDTO start(
        @PathVariable Long orderId, @AuthenticationPrincipal Long userId, HttpServletRequest request
    ) {
        var start = paymentService.start(userId, orderId, request.getRemoteAddr());
        var response = mapper.toDto(start);

        return response;
    }
}
