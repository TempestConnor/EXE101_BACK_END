package com.exe101.ledger.model;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One money event: its lines add up to 0, and each (type, source) is recorded once.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class LedgerPosting {
    private LedgerEventType type;
    private LedgerSourceType sourceType;
    private Long sourceId;
    private OffsetDateTime occurredAt;
    private List<LedgerLine> lines;
}
