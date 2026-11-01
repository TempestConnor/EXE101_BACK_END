package com.exe101.refund.mapper;

import com.exe101.refund.dto.DecideRefundDTO;
import com.exe101.refund.dto.RecordTransferOutcomeDTO;
import com.exe101.refund.dto.RefundRequestDTO;
import com.exe101.refund.dto.RefundTransferDTO;
import com.exe101.refund.model.Refund;
import com.exe101.refund.model.RefundDecision;
import com.exe101.refund.model.Transfer;
import com.exe101.refund.model.TransferOutcome;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RefundMapper {
    @Mapping(source = "decision", target = "withVerdict")
    @Mapping(source = "reason", target = "withReason")
    RefundDecision toModel(DecideRefundDTO dto);

    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "transferReference", target = "withTransferReference")
    @Mapping(source = "reason", target = "withReason")
    TransferOutcome toModel(RecordTransferOutcomeDTO dto);

    RefundRequestDTO toDto(Refund refund);

    List<RefundRequestDTO> toDto(List<Refund> refunds);

    RefundTransferDTO toDto(Transfer transfer);
}
