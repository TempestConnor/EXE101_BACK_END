package com.exe101.ledger.repository;

import java.math.BigDecimal;

public interface QuoteBalance {
    Long getQuoteId();

    String getAccount();

    BigDecimal getAmount();
}
