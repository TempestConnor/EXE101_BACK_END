package com.exe101.commission.model;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// The terms an Artist offers (§19.3); the tax and every financial snapshot are computed by the server.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class QuoteTerms {
    private BigDecimal price;
    private String scopeText;
    private String expectedCompletionTerms;
}
