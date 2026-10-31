package com.exe101.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record CreateOrderLineDTO(
    @NotNull @Positive Long listingId,
    @NotNull @Positive Integer quantity,
    @NotNull @PositiveOrZero BigDecimal unitPrice
) {
}
