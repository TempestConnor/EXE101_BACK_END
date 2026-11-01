package com.exe101.ledger.repository;

import java.math.BigDecimal;

public interface QuoteProceedsState {
    Long getProceedsId();

    Long getQuoteId();

    BigDecimal getAmount();

    String getStatus();
}
