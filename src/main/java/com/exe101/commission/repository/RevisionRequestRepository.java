package com.exe101.commission.repository;

import com.exe101.entity.RevisionRequest;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevisionRequestRepository extends JpaRepository<RevisionRequest, Long> {
    List<RevisionRequest> findByCommissionIdOrderByRevisionIdAsc(long commissionId);
}
