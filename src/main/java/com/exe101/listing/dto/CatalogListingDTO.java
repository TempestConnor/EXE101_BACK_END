package com.exe101.listing.dto;

import java.math.BigDecimal;

public record CatalogListingDTO(
    Long listingId, Long artworkId, String listingType, String name, String description, String specifications,
    String currencyCode, BigDecimal price, boolean purchasable
) {
}
