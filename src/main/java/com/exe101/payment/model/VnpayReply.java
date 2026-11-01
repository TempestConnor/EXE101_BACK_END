package com.exe101.payment.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// The response codes VNPay expects from a payment notification (IPN) endpoint.
@Getter
@RequiredArgsConstructor
public enum VnpayReply {
    CONFIRMED("00", "Confirm Success"),
    ORDER_NOT_FOUND("01", "Order not found"),
    ALREADY_CONFIRMED("02", "Order already confirmed"),
    INVALID_AMOUNT("04", "Invalid amount"),
    INVALID_SIGNATURE("97", "Invalid signature");

    private final String code;
    private final String message;
}
