package com.exe101.cart.dto;

import java.math.BigDecimal;

public record CheckoutLineDTO(
    Long listingId, String listingType, String name, BigDecimal unitPrice, int quantity, BigDecimal lineTotal,
    BigDecimal taxAmount, String problem
) {
}
