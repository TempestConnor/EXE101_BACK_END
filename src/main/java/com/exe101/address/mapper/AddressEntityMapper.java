package com.exe101.address.mapper;

import com.exe101.address.model.Address;
import com.exe101.address.model.AddressDetails;
import com.exe101.address.model.ShippingAddress;
import com.exe101.entity.CustomerAddress;
import java.util.List;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AddressEntityMapper {
    @BeanMapping(ignoreByDefault = true)
    @Mapping(source = "label", target = "label")
    @Mapping(source = "recipientName", target = "recipientName")
    @Mapping(source = "phone", target = "phone")
    @Mapping(source = "line1", target = "line1")
    @Mapping(source = "line2", target = "line2")
    @Mapping(source = "ward", target = "ward")
    @Mapping(source = "district", target = "district")
    @Mapping(source = "provinceCode", target = "provinceCode")
    @Mapping(source = "postalCode", target = "postalCode")
    @Mapping(source = "countryCode", target = "countryCode")
    @Mapping(source = "defaultAddress", target = "isDefault")
    void update(AddressDetails details, @MappingTarget CustomerAddress entity);

    @Mapping(source = "addressId", target = "withAddressId")
    @Mapping(source = "userId", target = "withUserId")
    @Mapping(source = "label", target = "withLabel")
    @Mapping(source = "recipientName", target = "withRecipientName")
    @Mapping(source = "phone", target = "withPhone")
    @Mapping(source = "line1", target = "withLine1")
    @Mapping(source = "line2", target = "withLine2")
    @Mapping(source = "ward", target = "withWard")
    @Mapping(source = "district", target = "withDistrict")
    @Mapping(source = "provinceCode", target = "withProvinceCode")
    @Mapping(source = "postalCode", target = "withPostalCode")
    @Mapping(source = "countryCode", target = "withCountryCode")
    @Mapping(source = "isDefault", target = "withDefaultAddress")
    @Mapping(source = "createdAt", target = "withCreatedAt")
    Address toModel(CustomerAddress entity);

    List<Address> toModel(List<CustomerAddress> entities);

    @Mapping(source = "entity.addressId", target = "withSourceAddressId")
    @Mapping(source = "entity.recipientName", target = "withRecipientName")
    @Mapping(source = "entity.phone", target = "withPhone")
    @Mapping(source = "entity.line1", target = "withLine1")
    @Mapping(source = "entity.line2", target = "withLine2")
    @Mapping(source = "entity.ward", target = "withWard")
    @Mapping(source = "entity.district", target = "withDistrict")
    @Mapping(source = "entity.provinceCode", target = "withProvinceCode")
    @Mapping(source = "provinceName", target = "withProvinceName")
    @Mapping(source = "entity.postalCode", target = "withPostalCode")
    @Mapping(source = "entity.countryCode", target = "withCountryCode")
    ShippingAddress toShipping(CustomerAddress entity, String provinceName);
}
