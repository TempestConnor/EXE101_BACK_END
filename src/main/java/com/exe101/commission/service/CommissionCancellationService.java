package com.exe101.commission.service;

import com.exe101.commission.model.CancellationRequest;
import com.exe101.commission.model.Commission;
import java.util.List;

public interface CommissionCancellationService {
    Commission cancel(Long userId, Long commissionId, String reason);

    List<CancellationRequest> list(Long userId, Long commissionId);

    CancellationRequest request(Long userId, Long commissionId, String reason);

    CancellationRequest accept(Long userId, Long commissionId, Long cancellationId, String note);

    CancellationRequest reject(Long userId, Long commissionId, Long cancellationId, String note);

    CancellationRequest withdraw(Long userId, Long commissionId, Long cancellationId);
}
