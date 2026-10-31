package com.exe101.cart.repository;

import com.exe101.entity.CartItem;
import com.exe101.entity.CartItemId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, CartItemId> {
    List<CartItem> findByIdCartIdOrderByIdListingIdAsc(long cartId);
}
