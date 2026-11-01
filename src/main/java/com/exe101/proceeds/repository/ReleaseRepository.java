package com.exe101.proceeds.repository;

import com.exe101.entity.Payout;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ReleaseRepository extends JpaRepository<Payout, Long> {
    // UPDLOCK until commit, taken before the release's proceeds.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payout> findWithLockByPayoutId(Long payoutId);

    List<Payout> findAllByOrderByCreatedAtDescPayoutIdDesc();

    List<Payout> findByStatusOrderByCreatedAtDescPayoutIdDesc(String status);

    List<Payout> findByArtistIdOrderByCreatedAtDescPayoutIdDesc(long artistId);
}
