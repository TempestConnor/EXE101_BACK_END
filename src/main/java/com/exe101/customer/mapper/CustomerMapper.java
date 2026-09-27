package com.exe101.customer.mapper;

import com.exe101.customer.dto.CustomerDTO;
import com.exe101.customer.dto.CustomerTokenDTO;
import com.exe101.customer.dto.LoginCustomerDTO;
import com.exe101.customer.dto.RegisterCustomerDTO;
import com.exe101.customer.model.Customer;
import com.exe101.customer.model.CustomerLogin;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.model.CustomerToken;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CustomerMapper {
    @Mapping(source = "email", target = "withEmail")
    @Mapping(source = "password", target = "withPassword")
    @Mapping(source = "displayName", target = "withDisplayName")
    CustomerRegistration toModel(RegisterCustomerDTO dto);

    @Mapping(source = "email", target = "withEmail")
    @Mapping(source = "password", target = "withPassword")
    CustomerLogin toModel(LoginCustomerDTO dto);

    CustomerDTO toDto(Customer customer);

    CustomerTokenDTO toDto(CustomerToken token);
}
