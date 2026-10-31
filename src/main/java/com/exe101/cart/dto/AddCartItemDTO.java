package com.exe101.cart.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddCartItemDTO(
    @NotNull @Positive Long listingId,
    @NotNull @Positive Integer quantity
) {
}
