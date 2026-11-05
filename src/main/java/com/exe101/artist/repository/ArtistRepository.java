package com.exe101.artist.repository;

import com.exe101.entity.ArtistProfile;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ArtistRepository extends JpaRepository<ArtistProfile, Long> {
    boolean existsByUserId(long userId);

    List<ArtistProfile> findAllByOrderByArtistIdAsc();

    Optional<ArtistProfile> findByUserId(long userId);

    // UPDLOCK until commit, in ID order: status changes wait for the Order that checked them.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ArtistProfile> findByArtistIdInOrderByArtistIdAsc(Collection<Long> artistIds);

    // UPDLOCK until commit: waits for Orders and publications that checked this Artist's status.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ArtistProfile> findWithLockByArtistId(Long artistId);

    // UPDLOCK until commit: a settings change waits for a status change in progress, and the reverse.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ArtistProfile> findWithLockByUserId(long userId);

    // Shared lock until commit: compatible with an Order's UPDLOCK, so publishing never deadlocks with checkout,
    // but a status change waits for it, and it waits for a status change in progress.
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<ArtistProfile> findWithSharedLockByArtistId(Long artistId);
}
