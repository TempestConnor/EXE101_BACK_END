package com.exe101.commission.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// What the Admin reviews (§26.9): the dispute, the Commission and its retained history.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class DisputeEvidence {
    private CommissionDispute dispute;
    private Commission commission;
    private List<CommissionMessage> messages;
    private List<CommissionQuote> quotes;
    private List<CommissionDeliverable> deliverables;
    private List<CancellationRequest> cancellationRequests;
}
