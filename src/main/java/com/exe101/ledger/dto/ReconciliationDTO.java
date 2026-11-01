package com.exe101.ledger.dto;

import java.util.List;

public record ReconciliationDTO(boolean clean, List<AccountBalanceDTO> balances, List<MismatchDTO> mismatches) {
}
