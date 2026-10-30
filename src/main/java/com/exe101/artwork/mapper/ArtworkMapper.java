package com.exe101.artwork.mapper;

import com.exe101.artwork.dto.ArtworkDTO;
import com.exe101.artwork.dto.ArtworkTagDTO;
import com.exe101.artwork.dto.ChangeArtworkStatusDTO;
import com.exe101.artwork.dto.CreateArtworkDTO;
import com.exe101.artwork.dto.MovePreviewDTO;
import com.exe101.artwork.dto.PreviewDTO;
import com.exe101.artwork.dto.UpdateArtworkDTO;
import com.exe101.artwork.model.Artwork;
import com.exe101.artwork.model.ArtworkDetails;
import com.exe101.artwork.model.ArtworkPreview;
import com.exe101.artwork.model.PreviewMove;
import com.exe101.artwork.model.PreviewUpload;
import com.exe101.artwork.model.StatusChange;
import com.exe101.tag.model.Tag;
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
public interface ArtworkMapper {
    @Mapping(source = "title", target = "withTitle")
    @Mapping(source = "description", target = "withDescription")
    @Mapping(source = "dimension", target = "withDimension")
    @Mapping(source = "tagIds", target = "withTagIds")
    @Mapping(target = "withVersion", ignore = true)
    ArtworkDetails toModel(CreateArtworkDTO dto);

    @Mapping(source = "title", target = "withTitle")
    @Mapping(source = "description", target = "withDescription")
    @Mapping(source = "dimension", target = "withDimension")
    @Mapping(source = "tagIds", target = "withTagIds")
    @Mapping(source = "version", target = "withVersion", qualifiedByName = "decodeVersion")
    ArtworkDetails toModel(UpdateArtworkDTO dto);

    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "reason", target = "withReason")
    StatusChange toModel(ChangeArtworkStatusDTO dto);

    @Mapping(source = "mediaId", target = "withMediaId")
    @Mapping(source = "dto.sortOrder", target = "withSortOrder")
    PreviewMove toModel(Long mediaId, MovePreviewDTO dto);

    @Mapping(source = "version", target = "version", qualifiedByName = "encodeVersion")
    ArtworkDTO toDto(Artwork artwork);

    List<ArtworkDTO> toDto(List<Artwork> artworks);

    ArtworkTagDTO toDto(Tag tag);

    PreviewDTO toDto(ArtworkPreview preview);

    default PreviewUpload toModel(MultipartFile file, Integer sortOrder) {
        byte[] bytes;

        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        PreviewUpload upload = PreviewUpload.builder()
            .withOriginalName(file.getOriginalFilename())
            .withBytes(bytes)
            .withSortOrder(sortOrder)
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
