package com.exe101.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// The body VNPay expects from its IPN endpoint.
public record VnpayIpnResponseDTO(@JsonProperty("RspCode") String rspCode, @JsonProperty("Message") String message) {
}
