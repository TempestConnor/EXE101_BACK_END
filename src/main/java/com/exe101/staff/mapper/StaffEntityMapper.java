package com.exe101.staff.mapper;

import com.exe101.entity.StaffAccount;
import com.exe101.staff.model.Staff;
import com.exe101.staff.model.StaffLogin;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StaffEntityMapper {
    @BeanMapping(ignoreByDefault = true)
    @Mapping(source = "loginName", target = "loginName")
    StaffAccount toEntity(StaffLogin login);

    @Mapping(source = "staffId", target = "withStaffId")
    @Mapping(source = "loginName", target = "withLoginName")
    @Mapping(source = "role", target = "withRole")
    Staff toModel(StaffAccount entity);
}
