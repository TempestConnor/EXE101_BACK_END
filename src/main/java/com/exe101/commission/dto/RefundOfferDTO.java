package com.exe101.commission.dto;

import jakarta.validation.constraints.NotNull;

public record RefundOfferDTO(@NotNull Integer refundPercent) {
}
