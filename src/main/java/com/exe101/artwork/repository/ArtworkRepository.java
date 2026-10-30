package com.exe101.artwork.repository;

import com.exe101.entity.Artwork;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtworkRepository extends JpaRepository<Artwork, Long> {
    List<Artwork> findByArtistIdOrderByCreatedAtDesc(long artistId);

    List<Artwork> findAllByOrderByCreatedAtDesc();

    List<Artwork> findByStatusOrderByPublishedAtDesc(String status);

    Optional<Artwork> findByArtworkIdAndStatus(Long artworkId, String status);
}
