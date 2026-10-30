package com.exe101.listing.mapper;

import com.exe101.listing.model.Listing;
import com.exe101.listing.model.ListingAsset;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ListingEntityMapper {
    @Mapping(source = "listingId", target = "withListingId")
    @Mapping(source = "artworkId", target = "withArtworkId")
    @Mapping(source = "listingType", target = "withListingType")
    @Mapping(source = "name", target = "withName")
    @Mapping(source = "description", target = "withDescription")
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "currencyCode", target = "withCurrencyCode")
    @Mapping(source = "price", target = "withPrice")
    @Mapping(source = "manufacturingCost", target = "withManufacturingCost")
    @Mapping(source = "artistMarkup", target = "withArtistMarkup")
    @Mapping(source = "specifications", target = "withSpecifications")
    @Mapping(source = "costReviewRequired", target = "withCostReviewRequired")
    @Mapping(source = "publishedAt", target = "withPublishedAt")
    @Mapping(source = "createdAt", target = "withCreatedAt")
    @Mapping(source = "rowVer", target = "withVersion")
    @Mapping(target = "withPurchasable", ignore = true)
    @Mapping(target = "withAssets", ignore = true)
    Listing toModel(com.exe101.entity.Listing entity);

    @Mapping(source = "listingAssetId", target = "withAssetId")
    @Mapping(source = "fileId", target = "withFileId")
    @Mapping(source = "sortOrder", target = "withSortOrder")
    ListingAsset toModel(com.exe101.entity.ListingAsset entity);
}
