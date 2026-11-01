package com.exe101.artist.repository;

import com.exe101.entity.ArtistSuspension;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// A deactivation is stored as an ArtistSuspension row with no end; UX_OpenSuspension allows one open row per Artist.
public interface ArtistDeactivationRepository extends JpaRepository<ArtistSuspension, Long> {
    Optional<ArtistSuspension> findByArtistIdAndClosedAtIsNull(long artistId);
}
