package com.exe101.entitlement.model;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Permanent access of the buyer to the files recorded on one Order Item; never changed after it is granted.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class Entitlement {
    private Long entitlementId;
    private Long orderItemId;
    private Long customerId;
    private OffsetDateTime grantedAt;
    private List<Long> listingAssetIds;
}
