package com.exe101.customer.repository;

import com.exe101.entity.MarketplaceUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<MarketplaceUser, Long> {
    boolean existsByNormalizedEmail(String normalizedEmail);

    Optional<MarketplaceUser> findByNormalizedEmail(String normalizedEmail);
}
