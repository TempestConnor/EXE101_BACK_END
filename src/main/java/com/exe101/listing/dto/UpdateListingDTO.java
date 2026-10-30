package com.exe101.listing.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record UpdateListingDTO(
    @NotBlank @Size(max = 240) String name,
    String description,
    @PositiveOrZero @Digits(integer = 15, fraction = 0) BigDecimal price,
    @Size(max = 40) String productCode,
    @PositiveOrZero @Digits(integer = 15, fraction = 0) BigDecimal artistMarkup,
    // Base64 of the 8-byte row version returned by the last read.
    @NotNull @Pattern(regexp = "[A-Za-z0-9+/]{11}=") String version
) {
}
