package com.exe101.ledger.repository;

import java.math.BigDecimal;

public interface ItemBalance {
    Long getOrderItemId();

    String getAccount();

    BigDecimal getAmount();
}
