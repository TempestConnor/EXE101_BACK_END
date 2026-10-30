package com.exe101.artwork.mapper;

import com.exe101.artwork.model.Artwork;
import com.exe101.artwork.model.ArtworkPreview;
import com.exe101.entity.ArtworkMedia;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ArtworkEntityMapper {
    @Mapping(source = "artworkId", target = "withArtworkId")
    @Mapping(source = "artistId", target = "withArtistId")
    @Mapping(source = "title", target = "withTitle")
    @Mapping(source = "description", target = "withDescription")
    @Mapping(source = "dimension", target = "withDimension")
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "publishedAt", target = "withPublishedAt")
    @Mapping(source = "createdAt", target = "withCreatedAt")
    @Mapping(source = "rowVer", target = "withVersion")
    @Mapping(target = "withPurchasable", ignore = true)
    @Mapping(target = "withTags", ignore = true)
    @Mapping(target = "withPreviews", ignore = true)
    Artwork toModel(com.exe101.entity.Artwork entity);

    @Mapping(source = "artworkMediaId", target = "withMediaId")
    @Mapping(source = "fileId", target = "withFileId")
    @Mapping(source = "sortOrder", target = "withSortOrder")
    ArtworkPreview toModel(ArtworkMedia entity);
}
