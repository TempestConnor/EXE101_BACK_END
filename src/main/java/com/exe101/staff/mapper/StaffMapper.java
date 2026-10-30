package com.exe101.staff.mapper;

import com.exe101.staff.dto.LoginStaffDTO;
import com.exe101.staff.dto.StaffTokenDTO;
import com.exe101.staff.model.StaffLogin;
import com.exe101.staff.model.StaffToken;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StaffMapper {
    @Mapping(source = "loginName", target = "withLoginName")
    @Mapping(source = "password", target = "withPassword")
    StaffLogin toModel(LoginStaffDTO dto);

    StaffTokenDTO toDto(StaffToken token);
}
