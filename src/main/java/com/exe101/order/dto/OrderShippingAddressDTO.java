package com.exe101.order.dto;

public record OrderShippingAddressDTO(
    String recipientName, String phone, String line1, String line2, String ward, String district,
    String provinceCode, String provinceName, String postalCode, String countryCode
) {
}
