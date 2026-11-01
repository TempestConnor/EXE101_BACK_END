package com.exe101.ledger.model;

import java.util.List;
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
public class Reconciliation {
    private boolean clean;
    private List<AccountBalance> balances;
    private List<Mismatch> mismatches;
}
