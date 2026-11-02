package com.exe101.commission.service;

import com.exe101.commission.model.CommissionRefund;
import com.exe101.refund.model.RefundStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface CommissionRefundService {
    void offer(Long commissionId, Long artistUserId, Integer percent, String reason, OffsetDateTime offeredAt);

    Optional<Integer> findOfferPercent(Long commissionId);

    void requireSettleable(Long commissionId);

    void closeOffer(Long commissionId, String reason, OffsetDateTime closedAt);

    void settleOffer(Long commissionId, OffsetDateTime acceptedAt);

    void settleByAdmin(Long commissionId, Long staffId, Integer percent, String reason, OffsetDateTime resolvedAt);

    void releaseOnCancellation(Long commissionId, OffsetDateTime cancelledAt);

    List<CommissionRefund> list(Long userId, Long commissionId);

    List<CommissionRefund> listForStaff(RefundStatus status);
}
