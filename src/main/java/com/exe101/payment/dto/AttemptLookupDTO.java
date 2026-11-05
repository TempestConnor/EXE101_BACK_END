package com.exe101.payment.dto;

// status is set only by VNPay's signed IPN: INITIATED or PENDING (not confirmed yet), SUCCEEDED, FAILED, or EXCEPTION
// (money arrived but could not be applied, such as a late or wrong-amount payment). Either orderId or the Quote
// fields (commissionId, quoteId, installment) are set.
public record AttemptLookupDTO(
    String attemptRef, String status, Long orderId, Long commissionId, Long quoteId, Integer installment
) {
}
