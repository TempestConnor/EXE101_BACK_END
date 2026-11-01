package com.exe101.commission.repository;

import com.exe101.entity.CommissionDeliverable;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommissionDeliverableRepository extends JpaRepository<CommissionDeliverable, Long> {
    List<CommissionDeliverable> findByCommissionIdOrderByVersionNoAsc(long commissionId);

    Optional<CommissionDeliverable> findByDeliverableIdAndCommissionId(Long deliverableId, long commissionId);

    // Read under the Commission lock, so the next number cannot be taken twice (UQ CommissionId, VersionNo).
    @Query("select coalesce(max(d.versionNo), 0) from CommissionDeliverable d where d.commissionId = :commissionId")
    int findLastVersionNo(@Param("commissionId") long commissionId);
}
