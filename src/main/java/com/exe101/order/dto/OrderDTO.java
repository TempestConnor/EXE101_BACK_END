package com.exe101.order.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record OrderDTO(
    Long orderId, String status, OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime paidAt,
    String currencyCode, BigDecimal itemsSubtotal, BigDecimal shippingAmount, BigDecimal taxAmount,
    BigDecimal totalAmount, String billingName, List<OrderItemDTO> items, OrderShippingAddressDTO shippingAddress,
    PaymentTargetDTO payment
) {
}
