package com.exe101.commission.repository;

import com.exe101.entity.CommissionEvent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommissionEventRepository extends JpaRepository<CommissionEvent, Long> {
    // The event that opened or resolved a dispute: it keeps the status to restore and the Admin's reason.
    Optional<CommissionEvent> findFirstByCommissionIdAndEventTypeAndRelatedIdOrderByEventIdDesc(
        long commissionId, String eventType, Long relatedId);
}
