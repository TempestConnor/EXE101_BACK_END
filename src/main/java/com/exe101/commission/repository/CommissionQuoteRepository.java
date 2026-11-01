package com.exe101.commission.repository;

import com.exe101.entity.CommissionQuote;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommissionQuoteRepository extends JpaRepository<CommissionQuote, Long> {
    List<CommissionQuote> findByCommissionIdOrderBySequenceNoAsc(long commissionId);

    Optional<CommissionQuote> findByQuoteIdAndCommissionId(Long quoteId, long commissionId);

    List<CommissionQuote> findByCommissionIdAndStatus(long commissionId, String status);

    // Read under the Commission lock, so the next number cannot be taken twice (UQ CommissionId, SequenceNo).
    @Query("select coalesce(max(q.sequenceNo), 0) from CommissionQuote q where q.commissionId = :commissionId")
    int findLastSequenceNo(@Param("commissionId") long commissionId);

    // Only the Commission ID, so the Quote is read after its Commission is locked.
    @Query("select q.commissionId from CommissionQuote q where q.quoteId = :quoteId")
    Optional<Long> findCommissionIdByQuoteId(@Param("quoteId") Long quoteId);
}
