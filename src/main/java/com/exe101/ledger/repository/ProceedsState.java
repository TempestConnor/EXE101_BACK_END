package com.exe101.ledger.repository;

import java.math.BigDecimal;

public interface ProceedsState {
    Long getProceedsId();

    Long getOrderItemId();

    BigDecimal getAmount();

    String getStatus();
}
