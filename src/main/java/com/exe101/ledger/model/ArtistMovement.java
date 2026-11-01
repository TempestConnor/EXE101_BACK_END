package com.exe101.ledger.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Money owed to one Artist for one item moving between the Artist accounts, caused by a proceeds or release record.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class ArtistMovement {
    private Long sourceId;
    private Long artistId;
    private Long orderItemId;
    private BigDecimal amount;
    private OffsetDateTime occurredAt;
}
