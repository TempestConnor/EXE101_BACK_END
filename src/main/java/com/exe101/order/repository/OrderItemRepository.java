package com.exe101.order.repository;

import com.exe101.entity.OrderItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findByOrderIdInOrderByOrderItemIdAsc(Collection<Long> orderIds);
}
