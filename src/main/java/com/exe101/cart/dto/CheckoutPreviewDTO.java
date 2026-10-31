package com.exe101.cart.dto;

import java.math.BigDecimal;
import java.util.List;

// Order creation (F10) receives cartVersion and these amounts back and rejects any difference.
public record CheckoutPreviewDTO(
    String cartVersion, String currencyCode, List<CheckoutLineDTO> lines, BigDecimal itemsSubtotal,
    BigDecimal shippingAmount, BigDecimal taxAmount, BigDecimal totalAmount, List<String> problems,
    boolean committable
) {
}
