package com.exe101.province.mapper;

import com.exe101.province.dto.CreateProvinceDTO;
import com.exe101.province.dto.ProvinceDTO;
import com.exe101.province.dto.UpdateProvinceSupportDTO;
import com.exe101.province.model.NewProvince;
import com.exe101.province.model.Province;
import com.exe101.province.model.ProvinceSupportChange;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProvinceMapper {
    @Mapping(source = "provinceCode", target = "withProvinceCode")
    @Mapping(source = "provinceName", target = "withProvinceName")
    NewProvince toModel(CreateProvinceDTO dto);

    @Mapping(source = "supported", target = "withSupported")
    @Mapping(source = "reason", target = "withReason")
    ProvinceSupportChange toModel(UpdateProvinceSupportDTO dto);

    ProvinceDTO toDto(Province province);

    List<ProvinceDTO> toDto(List<Province> provinces);
}
