package com.exe101.listing.repository;

import com.exe101.entity.Listing;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingRepository extends JpaRepository<Listing, Long> {
    List<Listing> findByArtworkIdOrderByCreatedAtAsc(long artworkId);

    List<Listing> findByArtworkIdAndStatusOrderByCreatedAtAsc(long artworkId, String status);

    List<Listing> findByArtworkIdInAndStatusAndCostReviewRequiredFalse(Collection<Long> artworkIds, String status);
}
