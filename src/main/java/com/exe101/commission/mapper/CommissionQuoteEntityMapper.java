package com.exe101.commission.mapper;

import com.exe101.commission.model.CommissionQuote;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CommissionQuoteEntityMapper {
    @Mapping(source = "quoteId", target = "withQuoteId")
    @Mapping(source = "commissionId", target = "withCommissionId")
    @Mapping(source = "sequenceNo", target = "withSequenceNo")
    @Mapping(source = "scopeText", target = "withScopeText")
    @Mapping(source = "expectedCompletionTerms", target = "withExpectedCompletionTerms")
    @Mapping(source = "price", target = "withPrice")
    @Mapping(source = "taxAmount", target = "withTaxAmount")
    @Mapping(source = "currencyCode", target = "withCurrencyCode")
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "offeredAt", target = "withOfferedAt")
    @Mapping(source = "acceptedAt", target = "withAcceptedAt")
    @Mapping(source = "paidAt", target = "withPaidAt")
    CommissionQuote toModel(com.exe101.entity.CommissionQuote entity);
}
