package com.exe101.cart.model;

// Why a Cart line or the whole checkout cannot be committed; reported in the response, never thrown.
public enum CheckoutProblem {
    CART_EMPTY,
    LISTING_UNAVAILABLE,
    OWN_LISTING,
    QUANTITY_NOT_ALLOWED,
    ADDRESS_REQUIRED,
    DELIVERY_NOT_SUPPORTED,
    AMOUNT_TOO_LARGE
}
