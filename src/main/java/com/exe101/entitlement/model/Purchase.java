package com.exe101.entitlement.model;

import com.exe101.listing.model.ListingType;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One entitlement as the buyer sees it: the Order Item's snapshot and the files it grants.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class Purchase {
    private Long entitlementId;
    private OffsetDateTime grantedAt;
    private Long orderId;
    private Long orderItemId;
    private Long listingId;
    private ListingType listingType;
    private String artworkTitle;
    private String listingName;
    private String artistName;
    private List<PurchasedFile> files;
}
