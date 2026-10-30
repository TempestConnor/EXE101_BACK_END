package com.exe101.artist.repository;

import com.exe101.entity.ArtistProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtistRepository extends JpaRepository<ArtistProfile, Long> {
    boolean existsByUserId(long userId);
}
