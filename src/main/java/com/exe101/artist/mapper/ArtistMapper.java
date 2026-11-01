package com.exe101.artist.mapper;

import com.exe101.artist.dto.ArtistDTO;
import com.exe101.artist.dto.ArtistDeactivationDTO;
import com.exe101.artist.dto.CommissionSettingsDTO;
import com.exe101.artist.dto.GrantArtistDTO;
import com.exe101.artist.dto.PublicArtistDTO;
import com.exe101.artist.model.Artist;
import com.exe101.artist.model.ArtistDeactivation;
import com.exe101.artist.model.ArtistGrant;
import com.exe101.artist.model.CommissionSettings;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

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

    PublicArtistDTO toPublicDto(Artist artist);

    ArtistDeactivationDTO toDto(ArtistDeactivation deactivation);
}
