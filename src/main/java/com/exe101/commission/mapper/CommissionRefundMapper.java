package com.exe101.commission.mapper;

import com.exe101.commission.dto.CommissionRefundDTO;
import com.exe101.commission.model.CommissionRefund;
import com.exe101.refund.dto.RefundTransferDTO;
import com.exe101.refund.model.Transfer;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CommissionRefundMapper {
    CommissionRefundDTO toDto(CommissionRefund refund);

    List<CommissionRefundDTO> toDto(List<CommissionRefund> refunds);

    RefundTransferDTO toDto(Transfer transfer);
}
