package com.exe101.commission.repository;

import com.exe101.entity.CommissionDispute;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommissionDisputeRepository extends JpaRepository<CommissionDispute, Long> {
    List<CommissionDispute> findByCommissionIdOrderByDisputeIdAsc(long commissionId);

    List<CommissionDispute> findAllByOrderByDisputeIdAsc();

    List<CommissionDispute> findByStatusOrderByDisputeIdAsc(String status);

    // Only the Commission ID, so the dispute is read after its Commission is locked.
    @Query("select d.commissionId from CommissionDispute d where d.disputeId = :disputeId")
    Optional<Long> findCommissionIdByDisputeId(@Param("disputeId") Long disputeId);
}
