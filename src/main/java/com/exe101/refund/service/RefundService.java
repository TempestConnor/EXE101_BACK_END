package com.exe101.refund.service;

import com.exe101.refund.model.Refund;
import com.exe101.refund.model.RefundDecision;
import com.exe101.refund.model.RefundStatus;
import com.exe101.refund.model.TransferOutcome;
import java.util.List;

public interface RefundService {
    Refund request(Long userId, Long orderId, Long orderItemId, String reason);

    List<Refund> listOwn(Long userId);

    List<Refund> list(RefundStatus status);

    Refund decide(Long staffId, Long refundRequestId, RefundDecision decision);

    Refund recordTransferOutcome(Long staffId, Long transferId, TransferOutcome outcome);
}
