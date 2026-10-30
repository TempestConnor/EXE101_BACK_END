package com.exe101.artwork.service;

import java.util.Collection;
import java.util.Set;

// Implemented by the Listing feature, so Artwork can report purchase availability without depending on it.
public interface ListingAvailability {
    Set<Long> findArtworksWithOfferedListings(Collection<Long> artworkIds);
}
