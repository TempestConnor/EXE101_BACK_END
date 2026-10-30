package com.exe101.artwork.repository;

import com.exe101.entity.ArtworkMedia;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtworkMediaRepository extends JpaRepository<ArtworkMedia, Long> {
    List<ArtworkMedia> findByArtworkIdInOrderBySortOrderAsc(Collection<Long> artworkIds);

    Optional<ArtworkMedia> findByArtworkMediaIdAndArtworkId(Long artworkMediaId, long artworkId);

    Optional<ArtworkMedia> findTopByArtworkIdOrderBySortOrderDesc(long artworkId);

    long countByArtworkId(long artworkId);

    boolean existsByArtworkIdAndSortOrder(long artworkId, int sortOrder);
}
