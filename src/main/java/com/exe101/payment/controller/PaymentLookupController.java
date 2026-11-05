package com.exe101.payment.controller;

import com.exe101.payment.dto.AttemptLookupDTO;
import com.exe101.payment.mapper.PaymentMapper;
import com.exe101.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// The buyer's page after VNPay looks up vnp_TxnRef here; it shows what the IPN recorded, never the return URL's own
// parameters.
@RestController
@RequestMapping("/payments/attempts")
@RequiredArgsConstructor
public class PaymentLookupController {
    private final PaymentService paymentService;
    private final PaymentMapper mapper;

    @GetMapping("/{attemptRef}")
    public AttemptLookupDTO get(@PathVariable String attemptRef, @AuthenticationPrincipal Long userId) {
        var lookup = paymentService.findAttempt(userId, attemptRef);
        var response = mapper.toDto(lookup);

        return response;
    }
}
