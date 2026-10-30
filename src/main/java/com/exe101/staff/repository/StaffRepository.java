package com.exe101.staff.repository;

import com.exe101.entity.StaffAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffRepository extends JpaRepository<StaffAccount, Long> {
    boolean existsByRole(String role);

    Optional<StaffAccount> findByLoginName(String loginName);
}
