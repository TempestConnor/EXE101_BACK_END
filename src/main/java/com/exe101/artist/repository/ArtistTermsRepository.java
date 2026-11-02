package com.exe101.artist.repository;

import com.exe101.entity.ArtistTerms;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ArtistTermsRepository extends JpaRepository<ArtistTerms, Long> {
    Optional<ArtistTerms> findFirstByArtistIdOrderByVersionNoDesc(long artistId);

    boolean existsByArtistId(long artistId);

    // HOLDLOCK on the Artist's latest version until commit: the key range after it stays locked, so a newer version
    // cannot be inserted until the Quote acceptance that read this one commits.
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<ArtistTerms> findFirstWithLockByArtistIdOrderByVersionNoDesc(long artistId);
}
