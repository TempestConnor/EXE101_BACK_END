package com.exe101.commission.mapper;

import com.exe101.commission.model.CancellationRequest;
import com.exe101.commission.model.CommissionDispute;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CommissionCancellationEntityMapper {
    @Mapping(source = "cancellationId", target = "withCancellationId")
    @Mapping(source = "commissionId", target = "withCommissionId")
    @Mapping(source = "requestedByUserId", target = "withRequestedByUserId")
    @Mapping(source = "reason", target = "withReason")
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "createdAt", target = "withCreatedAt")
    @Mapping(source = "resolvedAt", target = "withResolvedAt")
    CancellationRequest toModel(com.exe101.entity.CancellationRequest entity);

    @Mapping(source = "disputeId", target = "withDisputeId")
    @Mapping(source = "commissionId", target = "withCommissionId")
    @Mapping(source = "openedByUserId", target = "withOpenedByUserId")
    @Mapping(source = "reason", target = "withReason")
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "outcome", target = "withOutcome")
    @Mapping(source = "decidedByStaffId", target = "withDecidedByStaffId")
    @Mapping(target = "withResolutionReason", ignore = true)
    @Mapping(source = "openedAt", target = "withOpenedAt")
    @Mapping(source = "resolvedAt", target = "withResolvedAt")
    CommissionDispute toModel(com.exe101.entity.CommissionDispute entity);
}
