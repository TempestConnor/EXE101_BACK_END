package com.exe101.commission.repository;

import com.exe101.entity.CommissionMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommissionMessageRepository extends JpaRepository<CommissionMessage, Long> {
    List<CommissionMessage> findByCommissionIdOrderByMessageIdAsc(long commissionId);
}
