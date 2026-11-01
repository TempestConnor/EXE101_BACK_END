package com.exe101.fulfillment.repository;

import com.exe101.entity.PhysicalFulfillment;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface FulfillmentRepository extends JpaRepository<PhysicalFulfillment, Long> {
    // UPDLOCK until commit, taken after the Order lock.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PhysicalFulfillment> findWithLockByOrderItemId(Long orderItemId);

    List<PhysicalFulfillment> findAllByOrderByUpdatedAtDescOrderItemIdDesc();

    List<PhysicalFulfillment> findByStatusOrderByUpdatedAtDescOrderItemIdDesc(String status);

    // Candidates only; each one is checked again under its locks before anything is granted.
    @Query("SELECT f.orderItemId FROM PhysicalFulfillment f"
        + " WHERE f.status = :delivered AND f.deliveredAt <= :closedBefore"
        + " AND NOT EXISTS (SELECT 1 FROM DigitalEntitlement e WHERE e.orderItemId = f.orderItemId)"
        + " ORDER BY f.deliveredAt, f.orderItemId")
    List<Long> findWindowClosed(String delivered, OffsetDateTime closedBefore);
}
