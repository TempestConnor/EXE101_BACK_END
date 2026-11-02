package com.exe101.commission.mapper;

import com.exe101.commission.dto.CancellationRequestDTO;
import com.exe101.commission.dto.CommissionDisputeDTO;
import com.exe101.commission.dto.DisputeEvidenceDTO;
import com.exe101.commission.dto.ResolveDisputeDTO;
import com.exe101.commission.dto.RespondCancellationDTO;
import com.exe101.commission.model.CancellationRequest;
import com.exe101.commission.model.CommissionDispute;
import com.exe101.commission.model.DisputeEvidence;
import com.exe101.commission.model.DisputeResolution;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR,
    uses = {CommissionMapper.class, CommissionQuoteMapper.class, CommissionDeliverableMapper.class})
public interface CommissionCancellationMapper {
    // The body of an answer is optional.
    default String toNote(RespondCancellationDTO dto) {
        return dto == null ? null : dto.note();
    }

    @Mapping(source = "outcome", target = "withOutcome")
    @Mapping(source = "reason", target = "withReason")
    @Mapping(source = "refundPercent", target = "withRefundPercent")
    DisputeResolution toModel(ResolveDisputeDTO dto);

    CancellationRequestDTO toDto(CancellationRequest request);

    List<CancellationRequestDTO> toCancellationDtos(List<CancellationRequest> requests);

    CommissionDisputeDTO toDto(CommissionDispute dispute);

    List<CommissionDisputeDTO> toDisputeDtos(List<CommissionDispute> disputes);

    DisputeEvidenceDTO toDto(DisputeEvidence evidence);
}
