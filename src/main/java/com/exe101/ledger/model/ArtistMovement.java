package com.exe101.ledger.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Money owed to one Artist for one Order Item or Quote moving between the Artist accounts, caused by a proceeds or
// release record. Exactly one of orderItemId and quoteId is set (CK_LedgerEntry_ArtistAccount).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class ArtistMovement {
    private Long sourceId;
    private Long artistId;
    private Long orderItemId;
    private Long quoteId;
    private BigDecimal amount;
    private OffsetDateTime occurredAt;
}
