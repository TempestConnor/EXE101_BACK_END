package com.exe101.refund.repository;

import com.exe101.entity.RefundRequest;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {
    // Only the item, so the request itself is first loaded under the Order lock and never read stale.
    @Query("SELECT r.orderItemId FROM RefundRequest r WHERE r.refundRequestId = :refundRequestId"
        + " AND r.orderItemId IS NOT NULL")
    Optional<Long> findOrderItemIdByRefundRequestId(Long refundRequestId);

    // UPDLOCK until commit, taken after the Order lock or the transfer lock.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefundRequest> findWithLockByRefundRequestId(Long refundRequestId);

    boolean existsByOrderItemIdAndStatusIn(Long orderItemId, Collection<String> statuses);

    List<RefundRequest> findByOrderItemIdInAndStatusIn(Collection<Long> orderItemIds, Collection<String> statuses);

    List<RefundRequest> findByRequestedByUserIdOrderByRequestedAtDescRefundRequestIdDesc(long requestedByUserId);

    List<RefundRequest> findByOrderItemIdIsNotNullOrderByRequestedAtDescRefundRequestIdDesc();

    List<RefundRequest> findByOrderItemIdIsNotNullAndStatusOrderByRequestedAtDescRefundRequestIdDesc(String status);

    // Only the Commission, so the request itself is first loaded under the Commission lock and never read stale.
    @Query("SELECT r.commissionId FROM RefundRequest r WHERE r.refundRequestId = :refundRequestId"
        + " AND r.commissionId IS NOT NULL")
    Optional<Long> findCommissionIdByRefundRequestId(Long refundRequestId);

    List<RefundRequest> findByCommissionIdOrderByRefundRequestIdAsc(Long commissionId);

    List<RefundRequest> findByCommissionIdIsNotNullOrderByRequestedAtDescRefundRequestIdDesc();

    List<RefundRequest> findByCommissionIdIsNotNullAndStatusOrderByRequestedAtDescRefundRequestIdDesc(String status);
}
