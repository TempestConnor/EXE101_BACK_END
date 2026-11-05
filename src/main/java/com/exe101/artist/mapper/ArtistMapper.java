package com.exe101.artist.mapper;

import com.exe101.artist.dto.ArtistDTO;
import com.exe101.artist.dto.ArtistDeactivationDTO;
import com.exe101.artist.dto.ArtistTermsDTO;
import com.exe101.artist.dto.ArtistTermsImageDTO;
import com.exe101.artist.dto.CommissionSettingsDTO;
import com.exe101.artist.dto.GrantArtistDTO;
import com.exe101.artist.dto.PublicArtistDTO;
import com.exe101.artist.dto.PublishTermsDTO;
import com.exe101.artist.model.Artist;
import com.exe101.artist.model.ArtistDeactivation;
import com.exe101.artist.model.ArtistGrant;
import com.exe101.artist.model.ArtistTerms;
import com.exe101.artist.model.ArtistTermsImage;
import com.exe101.artist.model.CommissionSettings;
import com.exe101.artist.model.TermsImageUpload;
import com.exe101.artist.model.TermsPublication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.springframework.web.multipart.MultipartFile;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ArtistMapper {
    @Mapping(source = "userId", target = "withUserId")
    @Mapping(source = "displayName", target = "withDisplayName")
    @Mapping(source = "reason", target = "withReason")
    ArtistGrant toModel(GrantArtistDTO dto);

    @Mapping(source = "acceptingCommissions", target = "withAcceptingCommissions")
    @Mapping(source = "startingPrice", target = "withStartingPrice")
    CommissionSettings toModel(CommissionSettingsDTO dto);

    ArtistDTO toDto(Artist artist);

    List<ArtistDTO> toDto(List<Artist> artists);

    PublicArtistDTO toPublicDto(Artist artist);

    ArtistDeactivationDTO toDto(ArtistDeactivation deactivation);

    ArtistTermsDTO toDto(ArtistTerms terms);

    ArtistTermsImageDTO toDto(ArtistTermsImage image);

    default TermsPublication toModel(PublishTermsDTO dto, List<MultipartFile> images) {
        List<TermsImageUpload> uploads = images == null ? List.of() : images.stream()
            .map(this::toUpload)
            .toList();
        TermsPublication publication = TermsPublication.builder()
            .withText(dto.text())
            .withKeepImageIds(dto.keepImageIds())
            .withUploads(uploads)
            .build();

        return publication;
    }

    default TermsImageUpload toUpload(MultipartFile file) {
        byte[] bytes;

        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        TermsImageUpload upload = TermsImageUpload.builder()
            .withOriginalName(file.getOriginalFilename())
            .withBytes(bytes)
            .build();

        return upload;
    }
}
