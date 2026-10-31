package com.exe101.listing.model;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// A Listing with its Artwork, Artist and files, read under lock while an Order is created from it.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class PurchaseItem {
    private Long listingId;
    private ListingType listingType;
    private String name;
    private String currencyCode;
    private BigDecimal price;
    private BigDecimal manufacturingCost;
    private BigDecimal artistMarkup;
    private String specifications;
    private boolean purchasable;
    private Long artworkId;
    private String artworkTitle;
    private Long artistId;
    private Long artistUserId;
    private String artistName;
    private List<ListingAsset> assets;
}
