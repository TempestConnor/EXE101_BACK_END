package com.exe101.order.model;

import com.exe101.listing.model.ListingType;
import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One purchased item as it was at Order creation; never changed afterwards.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class OrderLine {
    private Long orderItemId;
    private Long listingId;
    private ListingType listingType;
    private Long artworkId;
    private Long artistId;
    private String artworkTitle;
    private String listingName;
    private String artistName;
    private String specifications;
    private int quantity;
    private String currencyCode;
    private BigDecimal unitPrice;
    private BigDecimal manufacturingCostPerUnit;
    private BigDecimal markupPerUnit;
    private BigDecimal feeRate;
    private BigDecimal feeAmount;
    private BigDecimal artistProceeds;
    private BigDecimal taxAmount;
    private BigDecimal lineSubtotal;
    private List<OrderAsset> assets;
}
