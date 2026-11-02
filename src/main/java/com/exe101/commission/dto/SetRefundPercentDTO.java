package com.exe101.commission.dto;

import jakarta.validation.constraints.NotNull;

public record SetRefundPercentDTO(@NotNull Integer percent) {
}
