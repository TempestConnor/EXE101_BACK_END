package com.exe101.order.model;

import com.exe101.address.model.ShippingAddress;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One Order Item's snapshot of what was bought and where it is to be sent, for the Admin who fulfills it.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class ShippableItem {
    private Long orderItemId;
    private Long orderId;
    private String artworkTitle;
    private String listingName;
    private String specifications;
    private int quantity;
    private ShippingAddress shippingAddress;
}
