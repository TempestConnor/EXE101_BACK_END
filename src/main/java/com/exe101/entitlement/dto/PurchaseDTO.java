package com.exe101.entitlement.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record PurchaseDTO(
    Long entitlementId, OffsetDateTime grantedAt, Long orderId, Long orderItemId, Long listingId,
    String listingType, String artworkTitle, String listingName, String artistName, List<PurchasedFileDTO> files
) {
}
