package com.exe101.commission.service;

import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionDeliverable;
import com.exe101.commission.model.DeliverableSubmission;
import com.exe101.commission.model.RevisionRequest;
import com.exe101.file.model.FileContent;
import java.util.List;

public interface CommissionDeliveryService {
    List<CommissionDeliverable> list(Long userId, Long commissionId);

    List<CommissionDeliverable> listForStaff(Long commissionId);

    CommissionDeliverable submit(Long userId, Long commissionId, DeliverableSubmission submission);

    Commission approve(Long userId, Long commissionId, Long deliverableId);

    RevisionRequest requestRevision(Long userId, Long commissionId, Long deliverableId, String details);

    FileContent readFile(Long userId, Long commissionId, Long deliverableId, Long fileId);
}
