package com.exe101.listing.service;

import com.exe101.file.model.FileContent;
import com.exe101.listing.model.AssetUpload;
import com.exe101.listing.model.CostReviewConfirmation;
import com.exe101.listing.model.Listing;
import com.exe101.listing.model.ListingDetails;
import com.exe101.listing.model.ListingStatusChange;
import com.exe101.listing.model.ManufacturingProduct;
import java.util.List;

public interface ListingService {
    List<ManufacturingProduct> listProducts();

    Listing create(Long userId, ListingDetails details);

    Listing update(Long userId, Long listingId, ListingDetails details);

    Listing changeStatus(Long userId, Long listingId, ListingStatusChange change);

    List<Listing> listOwn(Long userId, Long artworkId);

    Listing getOwn(Long userId, Long listingId);

    Listing addAsset(Long userId, Long listingId, AssetUpload upload);

    void removeAsset(Long userId, Long listingId, Long assetId);

    FileContent getOwnAsset(Long userId, Long listingId, Long assetId);

    List<Listing> listForArtwork(Long artworkId);

    Listing get(Long listingId);

    Listing changeStatusAsAdmin(Long staffId, Long listingId, ListingStatusChange change);

    FileContent getAsset(Long listingId, Long assetId);

    List<Listing> listPublished(Long artworkId);

    int flagCostChanges();

    Listing confirmCostReview(Long userId, Long listingId, CostReviewConfirmation confirmation);
}
