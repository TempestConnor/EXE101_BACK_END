package com.exe101.cart.repository;

import com.exe101.entity.CartItem;
import com.exe101.entity.CartItemId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CartItemRepository extends JpaRepository<CartItem, CartItemId> {
    List<CartItem> findByIdCartIdOrderByIdListingIdAsc(long cartId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CartItem i WHERE i.id.cartId = :cartId")
    int deleteByCartId(long cartId);
}
