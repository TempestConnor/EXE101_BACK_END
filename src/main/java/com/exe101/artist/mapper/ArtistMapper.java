package com.exe101.artist.mapper;

import com.exe101.artist.dto.ArtistDTO;
import com.exe101.artist.dto.GrantArtistDTO;
import com.exe101.artist.model.Artist;
import com.exe101.artist.model.ArtistGrant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ArtistMapper {
    @Mapping(source = "userId", target = "withUserId")
    @Mapping(source = "displayName", target = "withDisplayName")
    @Mapping(source = "reason", target = "withReason")
    ArtistGrant toModel(GrantArtistDTO dto);

    ArtistDTO toDto(Artist artist);
}
