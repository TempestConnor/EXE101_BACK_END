package com.exe101.order.repository;

import com.exe101.entity.RefundRequest;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

// Read-only here: the refund feature writes these rows; an Order only shows each item's latest request.
public interface ItemRefundRepository extends JpaRepository<RefundRequest, Long> {
    List<RefundRequest> findByOrderItemIdInOrderByRefundRequestIdAsc(Collection<Long> orderItemIds);
}
