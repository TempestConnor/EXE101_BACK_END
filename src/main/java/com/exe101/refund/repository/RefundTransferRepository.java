package com.exe101.refund.repository;

import com.exe101.entity.RefundTransfer;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RefundTransferRepository extends JpaRepository<RefundTransfer, Long> {
    // UPDLOCK until commit, taken before the transfer's request.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefundTransfer> findWithLockByRefundTransferId(Long refundTransferId);

    List<RefundTransfer> findByRefundRequestIdIn(Collection<Long> refundRequestIds);

    @Query("SELECT COALESCE(SUM(x.amount), 0) FROM RefundTransfer x WHERE x.paymentId = :paymentId")
    BigDecimal sumAmountByPaymentId(long paymentId);
}
