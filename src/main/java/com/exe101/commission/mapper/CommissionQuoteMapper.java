package com.exe101.commission.mapper;

import com.exe101.commission.dto.CommissionQuoteDTO;
import com.exe101.commission.dto.OfferQuoteDTO;
import com.exe101.commission.model.CommissionQuote;
import com.exe101.commission.model.QuoteTerms;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CommissionQuoteMapper {
    @Mapping(source = "price", target = "withPrice")
    @Mapping(source = "scopeText", target = "withScopeText")
    @Mapping(source = "expectedCompletionTerms", target = "withExpectedCompletionTerms")
    QuoteTerms toModel(OfferQuoteDTO dto);

    @Mapping(target = "totalAmount", expression = "java(quote.getPrice().add(quote.getTaxAmount()))")
    CommissionQuoteDTO toDto(CommissionQuote quote);

    List<CommissionQuoteDTO> toDtos(List<CommissionQuote> quotes);
}
