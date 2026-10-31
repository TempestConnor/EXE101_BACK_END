package com.exe101.order.dto;

import java.math.BigDecimal;
import java.util.List;

// Manufacturing cost, markup, fee and Artist proceeds are recorded but not shown to the buyer.
public record OrderItemDTO(
    Long orderItemId, Long listingId, String listingType, Long artworkId, String artworkTitle, String listingName,
    Long artistId, String artistName, String specifications, int quantity, BigDecimal unitPrice,
    BigDecimal lineSubtotal, BigDecimal taxAmount, List<Long> assetIds
) {
}
