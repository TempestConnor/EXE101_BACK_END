package com.exe101.cart.repository;

import com.exe101.entity.Cart;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByUserId(long userId);

    // Every item change updates the Cart row, so its RowVer changes; 0 rows means another request changed it first.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Cart c SET c.updatedAt = :updatedAt WHERE c.cartId = :cartId AND c.rowVer = :version")
    int touch(long cartId, byte[] version, OffsetDateTime updatedAt);
}
