package com.exe101.ledger.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Money returned to the buyer for one item or one Commission's Quote, caused by a refund request (approval) or its
// transfer (payment). The parts are what the item or Quote still owed when it was refunded; the amount is what the
// buyer gets back.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class RefundMovement {
    private Long sourceId;
    private Long artistId;
    private Long orderItemId;
    private Long quoteId;
    private BigDecimal proceeds;
    private BigDecimal manufacturing;
    private BigDecimal tax;
    private BigDecimal shipping;
    private BigDecimal amount;
    private OffsetDateTime occurredAt;
}
