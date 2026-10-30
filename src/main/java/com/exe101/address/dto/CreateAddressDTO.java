package com.exe101.address.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAddressDTO(
    @Size(max = 80) String label,
    @NotBlank @Size(max = 160) String recipientName,
    // Vietnamese number: 0 followed by nine digits.
    @NotBlank @Pattern(regexp = "0\\d{9}") String phone,
    @NotBlank @Size(max = 240) String line1,
    @Size(max = 240) String line2,
    @NotBlank @Size(max = 160) String ward,
    @Size(max = 160) String district,
    @NotBlank @Size(max = 20) String provinceCode,
    // Vietnamese postal code: five digits.
    @Pattern(regexp = "\\d{5}") String postalCode,
    @Pattern(regexp = "VN") String countryCode,
    boolean defaultAddress
) {
}
