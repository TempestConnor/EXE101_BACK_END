package com.exe101.fulfillment.model;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One recorded transition. The first step has no previous status and no staff actor: the payment started it.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class FulfillmentStep {
    private Long orderItemId;
    private FulfillmentStatus fromStatus;
    private FulfillmentStatus toStatus;
    private String reason;
    private OffsetDateTime occurredAt;
    private Long actorStaffId;
}
