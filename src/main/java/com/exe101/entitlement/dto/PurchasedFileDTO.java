package com.exe101.entitlement.dto;

public record PurchasedFileDTO(Long assetId, String fileName, String contentType, long byteLength) {
}
