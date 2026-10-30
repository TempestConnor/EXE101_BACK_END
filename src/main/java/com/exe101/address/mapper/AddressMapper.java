package com.exe101.address.mapper;

import com.exe101.address.dto.AddressDTO;
import com.exe101.address.dto.CreateAddressDTO;
import com.exe101.address.dto.UpdateAddressDTO;
import com.exe101.address.model.Address;
import com.exe101.address.model.AddressDetails;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AddressMapper {
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
    @Mapping(source = "defaultAddress", target = "withDefaultAddress")
    AddressDetails toModel(CreateAddressDTO dto);

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
    @Mapping(source = "defaultAddress", target = "withDefaultAddress")
    AddressDetails toModel(UpdateAddressDTO dto);

    AddressDTO toDto(Address address);

    List<AddressDTO> toDto(List<Address> addresses);
}
