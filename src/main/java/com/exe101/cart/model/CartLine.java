package com.exe101.cart.model;

import com.exe101.listing.model.ListingType;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One Cart item at the Listing's current price; the Cart itself stores no price.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class CartLine {
    private Long listingId;
    private ListingType listingType;
    private String name;
    private String currencyCode;
    private BigDecimal unitPrice;
    private int quantity;
    private BigDecimal lineTotal;
    private CheckoutProblem problem;
}
