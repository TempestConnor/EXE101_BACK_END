package com.exe101.artist.mapper;

import com.exe101.artist.model.Artist;
import com.exe101.artist.model.ArtistGrant;
import com.exe101.entity.ArtistProfile;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ArtistEntityMapper {
    @BeanMapping(ignoreByDefault = true)
    @Mapping(source = "userId", target = "userId")
    @Mapping(source = "displayName", target = "displayName")
    ArtistProfile toEntity(ArtistGrant grant);

    @Mapping(source = "artistId", target = "withArtistId")
    @Mapping(source = "userId", target = "withUserId")
    @Mapping(source = "displayName", target = "withDisplayName")
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "acceptingCommissions", target = "withAcceptingCommissions")
    @Mapping(source = "authorizedByStaffId", target = "withAuthorizedByStaffId")
    @Mapping(source = "createdAt", target = "withCreatedAt")
    Artist toModel(ArtistProfile entity);
}
