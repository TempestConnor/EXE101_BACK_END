package com.exe101.listing.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

// Whole VND markup, kept or changed; retail becomes the current manufacturing cost plus this markup.
public record ConfirmCostReviewDTO(
    @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 0) BigDecimal artistMarkup,
    // Base64 of the 8-byte row version returned by the last read.
    @NotNull @Pattern(regexp = "[A-Za-z0-9+/]{11}=") String version
) {
}
