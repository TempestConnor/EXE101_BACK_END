package com.exe101.listing.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class Listing {
    private Long listingId;
    private Long artworkId;
    private ListingType listingType;
    private String name;
    private String description;
    private ListingStatus status;
    private String currencyCode;
    private BigDecimal price;
    private BigDecimal manufacturingCost;
    private BigDecimal artistMarkup;
    private String specifications;
    private boolean costReviewRequired;
    private OffsetDateTime publishedAt;
    private OffsetDateTime createdAt;
    private byte[] version;
    private boolean purchasable;
    private List<ListingAsset> assets;
}
