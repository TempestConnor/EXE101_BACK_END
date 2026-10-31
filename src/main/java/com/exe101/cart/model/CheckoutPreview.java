package com.exe101.cart.model;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Current amounts the Customer reviews; Order creation (F10) receives them back and rejects any difference.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class CheckoutPreview {
    private byte[] cartVersion;
    private String currencyCode;
    private List<CheckoutLine> lines;
    private BigDecimal itemsSubtotal;
    private BigDecimal shippingAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private List<CheckoutProblem> problems;
    private boolean committable;
}
