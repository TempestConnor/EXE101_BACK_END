package com.exe101.payment.controller;

import com.exe101.payment.dto.VnpayIpnResponseDTO;
import com.exe101.payment.mapper.PaymentMapper;
import com.exe101.payment.service.PaymentService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// VNPay calls this server to server with no bearer token; the service authenticates it by its signature. VNPay
// reads the RspCode of a 200 response, so even a rejected signature is answered with 200.
@RestController
@RequestMapping("/payments/vnpay")
@RequiredArgsConstructor
public class VnpayNotificationController {
    private final PaymentService paymentService;
    private final PaymentMapper mapper;

    @GetMapping("/ipn")
    public VnpayIpnResponseDTO notify(@RequestParam Map<String, String> parameters) {
        var result = paymentService.handleVnpayNotification(parameters);
        var response = mapper.toDto(result.getReply());

        return response;
    }
}
