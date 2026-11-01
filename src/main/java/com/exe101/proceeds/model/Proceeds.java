package com.exe101.proceeds.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// What one Order Item earns its Artist: the snapshotted proceeds, held until the item's release milestone.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class Proceeds {
    private Long proceedsId;
    private Long artistId;
    private Long orderItemId;
    private BigDecimal amount;
    private String currencyCode;
    private ProceedsStatus status;
    private OffsetDateTime eligibleAt;
    private OffsetDateTime paidAt;
}
