package com.exe101.order.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// A Listing file included with a purchased item, recorded at Order creation.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class OrderAsset {
    private Long listingAssetId;
    private Long fileId;
}
