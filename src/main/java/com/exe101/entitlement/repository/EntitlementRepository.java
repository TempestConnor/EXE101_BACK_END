package com.exe101.entitlement.repository;

import com.exe101.entity.DigitalEntitlement;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntitlementRepository extends JpaRepository<DigitalEntitlement, Long> {
    boolean existsByOrderItemId(long orderItemId);

    List<DigitalEntitlement> findByCustomerIdOrderByGrantedAtDescEntitlementIdDesc(long customerId);
}
