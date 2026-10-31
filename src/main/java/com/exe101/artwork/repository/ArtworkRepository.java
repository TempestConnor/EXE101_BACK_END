package com.exe101.artwork.repository;

import com.exe101.entity.Artwork;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ArtworkRepository extends JpaRepository<Artwork, Long> {
    List<Artwork> findByArtistIdOrderByCreatedAtDesc(long artistId);

    List<Artwork> findAllByOrderByCreatedAtDesc();

    List<Artwork> findByStatusOrderByPublishedAtDesc(String status);

    Optional<Artwork> findByArtworkIdAndStatus(Long artworkId, String status);

    // UPDLOCK until commit, in ID order: status changes wait for the Order that checked them.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Artwork> findByArtworkIdInOrderByArtworkIdAsc(Collection<Long> artworkIds);
}
