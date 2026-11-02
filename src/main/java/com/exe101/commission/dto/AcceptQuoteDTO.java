package com.exe101.commission.dto;

import jakarta.validation.constraints.NotNull;

// The version of the Artist's terms the Customer read, from GET /artists/{artistId}/terms.
public record AcceptQuoteDTO(@NotNull Long artistTermsId) {
}
