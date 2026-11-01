package com.exe101.entitlement.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// A granted file, downloaded through its Listing asset ID.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class PurchasedFile {
    private Long assetId;
    private String fileName;
    private String contentType;
    private long byteLength;
}
