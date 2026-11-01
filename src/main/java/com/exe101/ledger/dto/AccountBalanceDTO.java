package com.exe101.ledger.dto;

import java.math.BigDecimal;

public record AccountBalanceDTO(String account, BigDecimal amount) {
}
