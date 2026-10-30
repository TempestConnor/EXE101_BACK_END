package com.exe101.address.model;

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
public class AddressDetails {
    private String label;
    private String recipientName;
    private String phone;
    private String line1;
    private String line2;
    private String ward;
    private String district;
    private String provinceCode;
    private String postalCode;
    private String countryCode;
    private boolean defaultAddress;
}
