package com.exe101.ledger.model;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class LedgerLine {
    private LedgerAccount account;
    private Long artistId;
    private Long orderItemId;
    private Long quoteId;
    private BigDecimal amount;
}
