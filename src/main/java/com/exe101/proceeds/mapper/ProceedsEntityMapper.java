package com.exe101.proceeds.mapper;

import com.exe101.entity.ArtistProceeds;
import com.exe101.entity.Payout;
import com.exe101.entity.PayoutLine;
import com.exe101.proceeds.model.Proceeds;
import com.exe101.proceeds.model.Release;
import java.math.BigDecimal;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProceedsEntityMapper {
    @Mapping(target = "proceedsId", ignore = true)
    ArtistProceeds toEntity(Proceeds proceeds);

    @Mapping(source = "proceedsId", target = "withProceedsId")
    @Mapping(source = "artistId", target = "withArtistId")
    @Mapping(source = "orderItemId", target = "withOrderItemId")
    @Mapping(source = "quoteId", target = "withQuoteId")
    @Mapping(source = "installment", target = "withInstallment")
    @Mapping(source = "amount", target = "withAmount")
    @Mapping(source = "currencyCode", target = "withCurrencyCode")
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "eligibleAt", target = "withEligibleAt")
    @Mapping(source = "paidAt", target = "withPaidAt")
    Proceeds toModel(ArtistProceeds entity);

    // The transfer is made by hand, so its reference is recorded under the MANUAL provider.
    @Mapping(target = "payoutId", ignore = true)
    @Mapping(target = "providerName", ignore = true)
    @Mapping(source = "transferReference", target = "providerPayoutRef")
    Payout toEntity(Release release);

    @Mapping(source = "entity.payoutId", target = "withPayoutId")
    @Mapping(source = "entity.artistId", target = "withArtistId")
    @Mapping(source = "proceedsId", target = "withProceedsId")
    @Mapping(source = "entity.amount", target = "withAmount")
    @Mapping(source = "entity.currencyCode", target = "withCurrencyCode")
    @Mapping(source = "entity.status", target = "withStatus")
    @Mapping(source = "entity.providerPayoutRef", target = "withTransferReference")
    @Mapping(source = "entity.createdAt", target = "withCreatedAt")
    @Mapping(source = "entity.paidAt", target = "withPaidAt")
    Release toModel(Payout entity, Long proceedsId);

    @Mapping(target = "payoutLineId", ignore = true)
    @Mapping(target = "isActive", constant = "true")
    PayoutLine toEntity(Long payoutId, Long proceedsId, BigDecimal amount);
}
