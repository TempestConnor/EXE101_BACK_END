package com.exe101.province.mapper;

import com.exe101.entity.SupportedProvince;
import com.exe101.province.model.NewProvince;
import com.exe101.province.model.Province;
import java.util.List;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProvinceEntityMapper {
    @BeanMapping(ignoreByDefault = true)
    @Mapping(source = "provinceCode", target = "provinceCode")
    @Mapping(source = "provinceName", target = "provinceName")
    SupportedProvince toEntity(NewProvince province);

    @Mapping(source = "provinceId", target = "withProvinceId")
    @Mapping(source = "provinceCode", target = "withProvinceCode")
    @Mapping(source = "provinceName", target = "withProvinceName")
    @Mapping(source = "isSupported", target = "withSupported")
    @Mapping(source = "updatedAt", target = "withUpdatedAt")
    Province toModel(SupportedProvince entity);

    List<Province> toModel(List<SupportedProvince> entities);
}
