package com.exe101.address.dto;

import java.time.OffsetDateTime;

public record AddressDTO(
    Long addressId, String label, String recipientName, String phone, String line1, String line2, String ward,
    String district, String provinceCode, String postalCode, String countryCode, boolean defaultAddress,
    OffsetDateTime createdAt
) {
}
