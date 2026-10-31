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

    Optional<ArtistProfile> findByUserId(long userId);

    // UPDLOCK until commit, in ID order: status changes wait for the Order that checked them.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ArtistProfile> findByArtistIdInOrderByArtistIdAsc(Collection<Long> artistIds);
}
