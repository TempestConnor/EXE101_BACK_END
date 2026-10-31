package com.exe101.listing.mapper;

import com.exe101.listing.dto.CatalogListingDTO;
import com.exe101.listing.dto.ChangeListingStatusDTO;
import com.exe101.listing.dto.ConfirmCostReviewDTO;
import com.exe101.listing.dto.CreateListingDTO;
import com.exe101.listing.dto.ListingAssetDTO;
import com.exe101.listing.dto.ListingDTO;
import com.exe101.listing.dto.ManufacturingProductDTO;
import com.exe101.listing.dto.UpdateListingDTO;
import com.exe101.listing.model.AssetUpload;
import com.exe101.listing.model.CostReviewConfirmation;
import com.exe101.listing.model.Listing;
import com.exe101.listing.model.ListingAsset;
import com.exe101.listing.model.ListingDetails;
import com.exe101.listing.model.ListingStatusChange;
import com.exe101.listing.model.ManufacturingProduct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.springframework.web.multipart.MultipartFile;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ListingMapper {
    @Mapping(source = "artworkId", target = "withArtworkId")
    @Mapping(source = "listingType", target = "withListingType")
    @Mapping(source = "name", target = "withName")
    @Mapping(source = "description", target = "withDescription")
    @Mapping(source = "price", target = "withPrice")
    @Mapping(source = "productCode", target = "withProductCode")
    @Mapping(source = "artistMarkup", target = "withArtistMarkup")
    @Mapping(target = "withVersion", ignore = true)
    ListingDetails toModel(CreateListingDTO dto);

    @Mapping(target = "withArtworkId", ignore = true)
    @Mapping(target = "withListingType", ignore = true)
    @Mapping(source = "name", target = "withName")
    @Mapping(source = "description", target = "withDescription")
    @Mapping(source = "price", target = "withPrice")
    @Mapping(source = "productCode", target = "withProductCode")
    @Mapping(source = "artistMarkup", target = "withArtistMarkup")
    @Mapping(source = "version", target = "withVersion", qualifiedByName = "decodeVersion")
    ListingDetails toModel(UpdateListingDTO dto);

    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "reason", target = "withReason")
    ListingStatusChange toModel(ChangeListingStatusDTO dto);

    @Mapping(source = "artistMarkup", target = "withArtistMarkup")
    @Mapping(source = "version", target = "withVersion", qualifiedByName = "decodeVersion")
    CostReviewConfirmation toModel(ConfirmCostReviewDTO dto);

    @Mapping(source = "version", target = "version", qualifiedByName = "encodeVersion")
    ListingDTO toDto(Listing listing);

    List<ListingDTO> toDto(List<Listing> listings);

    ListingAssetDTO toDto(ListingAsset asset);

    CatalogListingDTO toCatalogDto(Listing listing);

    List<CatalogListingDTO> toCatalogDto(List<Listing> listings);

    ManufacturingProductDTO toDto(ManufacturingProduct product);

    List<ManufacturingProductDTO> toProductDto(List<ManufacturingProduct> products);

    default AssetUpload toModel(MultipartFile file) {
        byte[] bytes;

        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        AssetUpload upload = AssetUpload.builder()
            .withOriginalName(file.getOriginalFilename())
            .withBytes(bytes)
            .build();

        return upload;
    }

    @Named("decodeVersion")
    default byte[] decodeVersion(String version) {
        if (version == null) {
            return null;
        }

        return Base64.getDecoder().decode(version);
    }

    @Named("encodeVersion")
    default String encodeVersion(byte[] version) {
        if (version == null) {
            return null;
        }

        return Base64.getEncoder().encodeToString(version);
    }
}
