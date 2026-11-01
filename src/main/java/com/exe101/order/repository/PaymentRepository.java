package com.exe101.order.repository;

import com.exe101.entity.Payment;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findByOrderIdIn(Collection<Long> orderIds);

    // Only the Order ID, so the Payment is not loaded before its Order is locked.
    @Query("SELECT p.orderId FROM Payment p WHERE p.paymentId = :paymentId")
    Optional<Long> findOrderIdByPaymentId(Long paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findWithLockByPaymentId(Long paymentId);

    // The pending Payment of an expired Order can no longer settle it.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Payment p SET p.status = :cancelled WHERE p.status = :pending AND p.orderId IN"
        + " (SELECT o.orderId FROM MarketplaceOrder o WHERE o.status = :expired)")
    int cancelForExpiredOrders(String pending, String cancelled, String expired);
}
