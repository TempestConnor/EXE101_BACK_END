package com.exe101.refund.repository;

import com.exe101.entity.ShippingRefund;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

// Keyed by OrderId, so an Order's shipping can be refunded only once (§16.13).
public interface ShippingRefundRepository extends JpaRepository<ShippingRefund, Long> {
    List<ShippingRefund> findByRefundRequestIdIn(Collection<Long> refundRequestIds);
}
