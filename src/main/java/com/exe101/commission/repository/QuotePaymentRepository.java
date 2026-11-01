package com.exe101.commission.repository;

import com.exe101.entity.Payment;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

// The Payments of Quote installments; Order Payments belong to the Order feature.
public interface QuotePaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByQuoteIdAndInstallment(Long quoteId, Integer installment);

    // Only the Quote ID, so the Payment is not loaded before its Commission is locked.
    @Query("SELECT p.quoteId FROM Payment p WHERE p.paymentId = :paymentId")
    Optional<Long> findQuoteIdByPaymentId(Long paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findWithLockByPaymentId(Long paymentId);
}
