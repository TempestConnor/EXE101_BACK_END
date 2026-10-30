package com.exe101.audit.repository;

import com.exe101.entity.AdminAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AdminAudit, Long> {
}
