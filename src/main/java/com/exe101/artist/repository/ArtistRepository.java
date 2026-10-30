package com.exe101.artist.repository;

import com.exe101.entity.ArtistProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtistRepository extends JpaRepository<ArtistProfile, Long> {
    boolean existsByUserId(long userId);

    Optional<ArtistProfile> findByUserId(long userId);
}
