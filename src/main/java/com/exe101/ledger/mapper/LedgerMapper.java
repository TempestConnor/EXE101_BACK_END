package com.exe101.ledger.mapper;

import com.exe101.ledger.dto.ReconciliationDTO;
import com.exe101.ledger.model.Reconciliation;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface LedgerMapper {
    ReconciliationDTO toDto(Reconciliation reconciliation);
}
