package com.exe101.commission.repository;

import com.exe101.entity.CancellationRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CancellationRequestRepository extends JpaRepository<CancellationRequest, Long> {
    List<CancellationRequest> findByCommissionIdOrderByCancellationIdAsc(long commissionId);

    Optional<CancellationRequest> findByCancellationIdAndCommissionId(Long cancellationId, long commissionId);

    // Read under the Commission lock; UX_Cancellation_Pending allows one PENDING request per Commission.
    Optional<CancellationRequest> findByCommissionIdAndStatus(long commissionId, String status);

    boolean existsByCommissionIdAndStatus(long commissionId, String status);
}
