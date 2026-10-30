package com.exe101.listing.model;

import java.math.BigDecimal;
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
public class ListingDetails {
    private Long artworkId;
    private ListingType listingType;
    private String name;
    private String description;
    private BigDecimal price;
    private String productCode;
    private BigDecimal artistMarkup;
    private byte[] version;
}
