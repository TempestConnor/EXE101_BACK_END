package com.exe101.artwork.repository;

import com.exe101.entity.ArtworkTag;
import com.exe101.entity.ArtworkTagId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtworkTagRepository extends JpaRepository<ArtworkTag, ArtworkTagId> {
    List<ArtworkTag> findByIdArtworkIdIn(Collection<Long> artworkIds);
}
