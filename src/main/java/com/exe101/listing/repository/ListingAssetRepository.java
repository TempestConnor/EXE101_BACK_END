package com.exe101.listing.repository;

import com.exe101.entity.ListingAsset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingAssetRepository extends JpaRepository<ListingAsset, Long> {
    List<ListingAsset> findByListingIdInOrderBySortOrderAsc(Collection<Long> listingIds);

    Optional<ListingAsset> findByListingAssetIdAndListingId(Long listingAssetId, long listingId);

    Optional<ListingAsset> findTopByListingIdOrderBySortOrderDesc(long listingId);

    long countByListingId(long listingId);
}
