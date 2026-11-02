package com.exe101.commission.service;

import com.exe101.commission.model.CommissionRefund;
import com.exe101.commission.model.CommissionRefundDecision;
import com.exe101.refund.model.RefundStatus;
import java.time.OffsetDateTime;
import java.util.List;

public interface CommissionRefundService {
    void openOnCancellation(
        Long commissionId, Long actorUserId, Long actorStaffId, String reason, OffsetDateTime cancelledAt
    );

    void releaseOnCancellation(Long commissionId, OffsetDateTime cancelledAt);

    List<CommissionRefund> list(Long userId, Long commissionId);

    CommissionRefund setPercent(Long userId, Long commissionId, Long refundRequestId, Integer percent);

    CommissionRefund accept(Long userId, Long commissionId, Long refundRequestId);

    CommissionRefund object(Long userId, Long commissionId, Long refundRequestId, String reason);

    List<CommissionRefund> listForStaff(RefundStatus status);

    CommissionRefund decide(Long staffId, Long refundRequestId, CommissionRefundDecision decision);
}
