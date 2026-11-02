package com.exe101.refund.service;

import com.exe101.refund.model.CommissionRefundSettlement;
import com.exe101.refund.model.Refund;
import com.exe101.refund.model.RefundDecision;
import com.exe101.refund.model.RefundStatus;
import com.exe101.refund.model.TransferOutcome;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface RefundService {
    Refund request(Long userId, Long orderId, Long orderItemId, String reason);

    List<Refund> listOwn(Long userId);

    List<Refund> list(RefundStatus status);

    Refund decide(Long staffId, Long refundRequestId, RefundDecision decision);

    Refund recordTransferOutcome(Long staffId, Long transferId, TransferOutcome outcome);

    Refund openForCommission(
        Long commissionId, Long customerId, String reason, Integer percent, OffsetDateTime openedAt
    );

    List<Refund> listForCommission(Long commissionId);

    List<Refund> listCommissionRefunds(RefundStatus status);

    Optional<Refund> findOpenForCommission(Long commissionId);

    Refund closeCommissionOffer(Long refundRequestId, String reason, OffsetDateTime closedAt);

    Refund settleCommission(CommissionRefundSettlement settlement);
}
