package com.exe101.commission.service;

import com.exe101.commission.model.CommissionDispute;
import com.exe101.commission.model.DisputeEvidence;
import com.exe101.commission.model.DisputeResolution;
import com.exe101.commission.model.DisputeStatus;
import java.util.List;

public interface CommissionDisputeService {
    CommissionDispute open(Long userId, Long commissionId, String reason);

    List<CommissionDispute> list(Long userId, Long commissionId);

    List<CommissionDispute> listForStaff(DisputeStatus status);

    DisputeEvidence evidence(Long disputeId);

    CommissionDispute review(Long staffId, Long disputeId);

    CommissionDispute resolve(Long staffId, Long disputeId, DisputeResolution resolution);
}
