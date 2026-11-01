package com.exe101.fulfillment.repository;

import com.exe101.entity.RefundRequest;
import org.springframework.data.jpa.repository.JpaRepository;

// Read-only here: refund and return requests are written by F15. Any request not REJECTED withholds the files.
public interface ReturnRequestRepository extends JpaRepository<RefundRequest, Long> {
    boolean existsByOrderItemIdAndStatusNot(Long orderItemId, String status);
}
