package com.exe101.order.repository;

import com.exe101.entity.OrderShippingAddress;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderShippingAddressRepository extends JpaRepository<OrderShippingAddress, Long> {
    List<OrderShippingAddress> findByOrderIdIn(Collection<Long> orderIds);
}
