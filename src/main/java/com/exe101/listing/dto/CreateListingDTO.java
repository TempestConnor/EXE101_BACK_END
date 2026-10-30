package com.exe101.listing.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

// Whole VND: price for DIGITAL; productCode and artistMarkup for PHYSICAL, whose price the server computes.
public record CreateListingDTO(
    @NotNull @Positive Long artworkId,
    @NotNull @Pattern(regexp = "DIGITAL|PHYSICAL") String listingType,
    @NotBlank @Size(max = 240) String name,
    String description,
    @PositiveOrZero @Digits(integer = 15, fraction = 0) BigDecimal price,
    @Size(max = 40) String productCode,
    @PositiveOrZero @Digits(integer = 15, fraction = 0) BigDecimal artistMarkup
) {
}
