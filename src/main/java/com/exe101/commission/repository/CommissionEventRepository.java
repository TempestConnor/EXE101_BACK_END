package com.exe101.commission.repository;

import com.exe101.entity.CommissionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommissionEventRepository extends JpaRepository<CommissionEvent, Long> {
}
