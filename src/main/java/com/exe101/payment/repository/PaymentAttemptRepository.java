package com.exe101.payment.repository;

import com.exe101.entity.PaymentAttempt;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {
    // UPDLOCK until commit: two notifications for one attempt are handled one after the other.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentAttempt> findWithLockByProviderNameAndProviderAttemptRef(String providerName, String attemptRef);

    // Without a lock: the buyer's return page only reads the status the IPN recorded.
    Optional<PaymentAttempt> findByProviderNameAndProviderAttemptRef(String providerName, String attemptRef);
}
