package com.exe101.commission.repository;

import com.exe101.entity.CommissionAttachment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommissionAttachmentRepository extends JpaRepository<CommissionAttachment, Long> {
    List<CommissionAttachment> findByCommissionIdOrderByAttachmentIdAsc(long commissionId);

    Optional<CommissionAttachment> findByAttachmentIdAndCommissionId(Long attachmentId, long commissionId);

    long countByCommissionId(long commissionId);
}
