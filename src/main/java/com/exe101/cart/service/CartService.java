package com.exe101.cart.service;

import com.exe101.cart.model.Cart;
import com.exe101.cart.model.CartItemChange;
import com.exe101.cart.model.CheckoutPreview;
import java.util.List;

public interface CartService {
    Cart get(Long userId);

    Cart addItem(Long userId, CartItemChange change);

    Cart updateItem(Long userId, Long listingId, Integer quantity);

    void removeItem(Long userId, Long listingId);

    CheckoutPreview preview(Long userId, Long addressId);

    List<Long> claimForOrder(Long userId, byte[] version);

    void clear(Long userId);
}
