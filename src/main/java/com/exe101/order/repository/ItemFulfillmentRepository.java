package com.exe101.order.repository;

import com.exe101.entity.PhysicalFulfillment;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

// Read-only here: the fulfillment feature writes these rows; an Order only shows them.
public interface ItemFulfillmentRepository extends JpaRepository<PhysicalFulfillment, Long> {
    List<PhysicalFulfillment> findByOrderItemIdIn(Collection<Long> orderItemIds);
}
