package com.exe101.order.dto;

import java.math.BigDecimal;

public record PaymentTargetDTO(Long paymentId, BigDecimal amount, String currencyCode, String status) {
}
