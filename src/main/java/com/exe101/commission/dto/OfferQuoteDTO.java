package com.exe101.commission.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record OfferQuoteDTO(
    @NotNull @Positive @Digits(integer = 15, fraction = 0) BigDecimal price,
    @NotBlank @Size(max = 5000) String scopeText,
    @NotBlank @Size(max = 1000) String expectedCompletionTerms
) {
}
