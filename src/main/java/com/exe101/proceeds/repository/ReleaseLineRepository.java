package com.exe101.proceeds.repository;

import com.exe101.entity.PayoutLine;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReleaseLineRepository extends JpaRepository<PayoutLine, Long> {
    Optional<PayoutLine> findByPayoutIdAndIsActiveTrue(long payoutId);

    List<PayoutLine> findByPayoutIdIn(Collection<Long> payoutIds);
}
