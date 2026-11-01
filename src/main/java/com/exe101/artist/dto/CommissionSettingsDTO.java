package com.exe101.artist.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record CommissionSettingsDTO(
    @NotNull Boolean acceptingCommissions,
    @PositiveOrZero @Digits(integer = 15, fraction = 0) BigDecimal startingPrice
) {
}
