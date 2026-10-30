package com.exe101.listing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record ListingDTO(
    Long listingId, Long artworkId, String listingType, String name, String description, String status,
    String currencyCode, BigDecimal price, BigDecimal manufacturingCost, BigDecimal artistMarkup,
    String specifications, boolean costReviewRequired, OffsetDateTime publishedAt, OffsetDateTime createdAt,
    String version, boolean purchasable, List<ListingAssetDTO> assets
) {
}
