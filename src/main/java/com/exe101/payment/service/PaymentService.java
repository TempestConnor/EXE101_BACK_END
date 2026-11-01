package com.exe101.payment.service;

import com.exe101.payment.model.NotificationResult;
import com.exe101.payment.model.PaymentStart;
import java.util.Map;

public interface PaymentService {
    PaymentStart start(Long userId, Long orderId, String clientIp);

    PaymentStart startQuote(Long userId, Long commissionId, Long quoteId, String clientIp);

    NotificationResult handleVnpayNotification(Map<String, String> parameters);
}
