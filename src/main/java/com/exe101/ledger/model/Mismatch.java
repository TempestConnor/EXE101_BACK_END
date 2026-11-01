package com.exe101.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One record the ledger and the business tables disagree about.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class Mismatch {
    private MismatchCheck check;
    private String recordType;
    private Long recordId;
}
