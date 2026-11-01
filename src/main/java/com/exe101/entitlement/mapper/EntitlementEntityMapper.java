package com.exe101.entitlement.mapper;

import com.exe101.entitlement.model.Entitlement;
import com.exe101.entity.DigitalEntitlement;
import com.exe101.entity.EntitlementAsset;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface EntitlementEntityMapper {
    @Mapping(target = "entitlementId", ignore = true)
    DigitalEntitlement toEntity(Entitlement entitlement);

    @Mapping(source = "entitlementId", target = "id.entitlementId")
    @Mapping(source = "listingAssetId", target = "id.listingAssetId")
    @Mapping(source = "orderItemId", target = "orderItemId")
    EntitlementAsset toEntity(Long entitlementId, Long listingAssetId, Long orderItemId);
}
