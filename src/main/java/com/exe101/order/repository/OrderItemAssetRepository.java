package com.exe101.order.repository;

import com.exe101.entity.OrderItemAsset;
import com.exe101.entity.OrderItemAssetId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemAssetRepository extends JpaRepository<OrderItemAsset, OrderItemAssetId> {
    List<OrderItemAsset> findByIdOrderItemIdIn(Collection<Long> orderItemIds);
}
