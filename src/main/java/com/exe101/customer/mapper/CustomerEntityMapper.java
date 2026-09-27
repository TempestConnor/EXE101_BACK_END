package com.exe101.customer.mapper;

import com.exe101.customer.model.Customer;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.entity.MarketplaceUser;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CustomerEntityMapper {
    @BeanMapping(ignoreByDefault = true)
    @Mapping(source = "email", target = "email")
    @Mapping(source = "displayName", target = "displayName")
    MarketplaceUser toEntity(CustomerRegistration registration);

    @Mapping(source = "userId", target = "withUserId")
    @Mapping(source = "email", target = "withEmail")
    @Mapping(source = "displayName", target = "withDisplayName")
    @Mapping(source = "createdAt", target = "withCreatedAt")
    Customer toModel(MarketplaceUser entity);
}
