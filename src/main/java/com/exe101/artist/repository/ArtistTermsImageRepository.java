package com.exe101.artist.repository;

import com.exe101.entity.ArtistTermsImage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtistTermsImageRepository extends JpaRepository<ArtistTermsImage, Long> {
    List<ArtistTermsImage> findByArtistTermsIdOrderBySortOrderAsc(long artistTermsId);

    Optional<ArtistTermsImage> findByArtistTermsImageIdAndArtistTermsId(Long artistTermsImageId, long artistTermsId);
}
