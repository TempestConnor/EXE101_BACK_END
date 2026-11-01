package com.exe101.fulfillment.repository;

import com.exe101.entity.RefundRequest;
import org.springframework.data.jpa.repository.JpaRepository;

// Read-only here: the refund feature writes these rows and calls this one to cancel items, so the read stays here
// rather than going through the refund service. Any request not REJECTED withholds the files.
public interface ReturnRequestRepository extends JpaRepository<RefundRequest, Long> {
    boolean existsByOrderItemIdAndStatusNot(Long orderItemId, String status);
}
