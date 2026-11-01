package com.exe101.entitlement.mapper;

import com.exe101.entitlement.dto.PurchaseDTO;
import com.exe101.entitlement.dto.PurchasedFileDTO;
import com.exe101.entitlement.model.Purchase;
import com.exe101.entitlement.model.PurchasedFile;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PurchaseMapper {
    PurchaseDTO toDto(Purchase purchase);

    List<PurchaseDTO> toDto(List<Purchase> purchases);

    PurchasedFileDTO toDto(PurchasedFile file);
}
