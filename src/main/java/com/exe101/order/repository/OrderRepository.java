package com.exe101.order.repository;

import com.exe101.entity.MarketplaceOrder;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<MarketplaceOrder, Long> {
    Optional<MarketplaceOrder> findByOrderIdAndCustomerId(Long orderId, long customerId);

    List<MarketplaceOrder> findByCustomerIdOrderByCreatedAtDescOrderIdDesc(long customerId);

    // UPDLOCK until commit: a payment success and the expiry job settle one Order one at a time.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MarketplaceOrder> findWithLockByOrderId(Long orderId);

    // Conditional: an Order paid or expired by another transaction is not touched.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE MarketplaceOrder o SET o.status = :expired"
        + " WHERE o.status = :pending AND o.expiresAt <= :now")
    int expireDue(OffsetDateTime now, String pending, String expired);
}
