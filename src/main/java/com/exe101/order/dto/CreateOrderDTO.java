package com.exe101.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

// cartVersion, lines and totalAmount are sent back from the checkout preview the Customer confirmed.
public record CreateOrderDTO(
    // Standard Base64 with padding, as the preview returns it.
    @NotBlank @Pattern(regexp = "^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$") String cartVersion,
    @Positive Long addressId,
    @NotEmpty List<@NotNull @Valid CreateOrderLineDTO> lines,
    @NotNull @PositiveOrZero BigDecimal totalAmount
) {
}
