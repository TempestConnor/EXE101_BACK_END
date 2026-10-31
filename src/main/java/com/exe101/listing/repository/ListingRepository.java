package com.exe101.listing.repository;

import com.exe101.entity.Listing;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ListingRepository extends JpaRepository<Listing, Long> {
    List<Listing> findByArtworkIdOrderByCreatedAtAsc(long artworkId);

    List<Listing> findByArtworkIdAndStatusOrderByCreatedAtAsc(long artworkId, String status);

    List<Listing> findByArtworkIdInAndStatusAndCostReviewRequiredFalse(Collection<Long> artworkIds, String status);

    // UPDLOCK until commit, in ID order: price, cost-flag and status changes wait for the Order that checked them.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Listing> findByListingIdInOrderByListingIdAsc(Collection<Long> listingIds);

    // Flags physical Listings of one product whose stored cost differs from the current cost; RowVer changes too.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Listing l SET l.costReviewRequired = true WHERE l.listingType = 'PHYSICAL'"
        + " AND l.specifications = :specifications AND l.status IN :statuses AND l.costReviewRequired = false"
        + " AND l.manufacturingCost <> :currentCost")
    int flagCostChanges(String specifications, BigDecimal currentCost, Collection<String> statuses);
}
