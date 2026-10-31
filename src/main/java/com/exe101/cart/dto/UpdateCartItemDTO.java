package com.exe101.cart.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UpdateCartItemDTO(@NotNull @Positive Integer quantity) {
}
