package com.exe101.cart.model;

import com.exe101.listing.model.ListingType;
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
public class CheckoutLine {
    private Long listingId;
    private ListingType listingType;
    private String name;
    private BigDecimal unitPrice;
    private int quantity;
    private BigDecimal lineTotal;
    private BigDecimal taxAmount;
    private CheckoutProblem problem;
}
