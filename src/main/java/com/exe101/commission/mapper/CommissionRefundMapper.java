package com.exe101.commission.mapper;

import com.exe101.commission.dto.CommissionRefundDTO;
import com.exe101.commission.dto.DecideCommissionRefundDTO;
import com.exe101.commission.model.CommissionRefund;
import com.exe101.commission.model.CommissionRefundDecision;
import com.exe101.refund.dto.RefundTransferDTO;
import com.exe101.refund.model.Transfer;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CommissionRefundMapper {
    @Mapping(source = "percent", target = "withPercent")
    @Mapping(source = "reason", target = "withReason")
    CommissionRefundDecision toModel(DecideCommissionRefundDTO dto);

    CommissionRefundDTO toDto(CommissionRefund refund);

    List<CommissionRefundDTO> toDto(List<CommissionRefund> refunds);

    RefundTransferDTO toDto(Transfer transfer);
}
