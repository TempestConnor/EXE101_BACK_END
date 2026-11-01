package com.exe101.payment.repository;

import com.exe101.entity.ProviderEvent;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ProviderEventRepository extends JpaRepository<ProviderEvent, Long> {
    // UPDLOCK, HOLDLOCK also locks the key while no row exists yet: a concurrent copy of the same notification
    // waits for the first to commit and then finds it.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ProviderEvent> findWithLockByProviderNameAndExternalEventId(String providerName, String externalEventId);
}
