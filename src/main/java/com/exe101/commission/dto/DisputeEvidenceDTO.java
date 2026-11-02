package com.exe101.commission.dto;

import java.util.List;

public record DisputeEvidenceDTO(
    CommissionDisputeDTO dispute, CommissionSummaryDTO commission, List<CommissionMessageDTO> messages,
    List<CommissionQuoteDTO> quotes, List<CommissionDeliverableDTO> deliverables,
    List<CancellationRequestDTO> cancellationRequests
) {
}
