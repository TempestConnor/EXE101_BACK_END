package com.exe101.listing.model;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// The current, live terms of a Listing as a buyer sees them; not a snapshot.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class PurchaseOffer {
    private Long listingId;
    private ListingType listingType;
    private String name;
    private String currencyCode;
    private BigDecimal price;
    private boolean purchasable;
    private Long artistUserId;
}
