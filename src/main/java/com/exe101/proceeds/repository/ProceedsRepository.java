package com.exe101.proceeds.repository;

import com.exe101.entity.ArtistProceeds;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ProceedsRepository extends JpaRepository<ArtistProceeds, Long> {
    boolean existsByOrderItemId(Long orderItemId);

    // UPDLOCK until commit, taken after the Order lock or the release lock.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ArtistProceeds> findWithLockByOrderItemId(Long orderItemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ArtistProceeds> findWithLockByProceedsId(Long proceedsId);

    List<ArtistProceeds> findByArtistIdOrderByProceedsIdDesc(long artistId);
}
