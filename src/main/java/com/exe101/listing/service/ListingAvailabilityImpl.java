package com.exe101.listing.service;

import com.exe101.artwork.service.ListingAvailability;
import com.exe101.entity.Listing;
import com.exe101.listing.model.ListingStatus;
import com.exe101.listing.repository.ListingRepository;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Offered: PUBLISHED and not waiting for a cost review. Artwork and Artist status are checked by the caller.
@Component
@RequiredArgsConstructor
public class ListingAvailabilityImpl implements ListingAvailability {
    private final ListingRepository repository;

    @Override
    public Set<Long> findArtworksWithOfferedListings(Collection<Long> artworkIds) {
        if (artworkIds == null || artworkIds.isEmpty()) {
            return Set.of();
        }

        return repository.findByArtworkIdInAndStatusAndCostReviewRequiredFalse(artworkIds,
                ListingStatus.PUBLISHED.name())
            .stream()
            .map(Listing::getArtworkId)
            .collect(Collectors.toSet());
    }
}
