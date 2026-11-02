package com.exe101.artist.mapper;

import com.exe101.artist.model.ArtistTerms;
import com.exe101.artist.model.ArtistTermsImage;
import com.exe101.file.model.StoredFileInfo;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ArtistTermsEntityMapper {
    @Mapping(source = "entity.artistTermsId", target = "withArtistTermsId")
    @Mapping(source = "entity.artistId", target = "withArtistId")
    @Mapping(source = "entity.versionNo", target = "withVersionNo")
    @Mapping(source = "entity.termsText", target = "withText")
    @Mapping(source = "entity.publishedAt", target = "withPublishedAt")
    @Mapping(source = "images", target = "withImages")
    ArtistTerms toModel(com.exe101.entity.ArtistTerms entity, List<ArtistTermsImage> images);

    @Mapping(source = "image.artistTermsImageId", target = "withImageId")
    @Mapping(source = "image.fileId", target = "withFileId")
    @Mapping(source = "image.sortOrder", target = "withSortOrder")
    @Mapping(source = "file.contentType", target = "withContentType")
    @Mapping(source = "file.byteLength", target = "withByteLength")
    ArtistTermsImage toModel(com.exe101.entity.ArtistTermsImage image, StoredFileInfo file);
}
