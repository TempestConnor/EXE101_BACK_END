package com.exe101.cart.dto;

import java.math.BigDecimal;

// Current price; problem is null when the item can be bought.
public record CartItemDTO(
    Long listingId, String listingType, String name, String currencyCode, BigDecimal unitPrice, int quantity,
    BigDecimal lineTotal, String problem
) {
}
