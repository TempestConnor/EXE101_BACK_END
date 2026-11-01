package com.exe101.ledger.mapper;

import com.exe101.entity.LedgerEntry;
import com.exe101.entity.LedgerTransaction;
import com.exe101.ledger.model.LedgerLine;
import com.exe101.ledger.model.LedgerPosting;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface LedgerEntityMapper {
    @Mapping(target = "transactionId", ignore = true)
    LedgerTransaction toEntity(LedgerPosting posting);

    @Mapping(target = "entryId", ignore = true)
    @Mapping(source = "transactionId", target = "transactionId")
    @Mapping(source = "line.account", target = "account")
    @Mapping(source = "line.artistId", target = "artistId")
    @Mapping(source = "line.orderItemId", target = "orderItemId")
    @Mapping(source = "line.amount", target = "amount")
    @Mapping(target = "currencyCode", constant = "VND")
    LedgerEntry toEntity(LedgerLine line, Long transactionId);
}
