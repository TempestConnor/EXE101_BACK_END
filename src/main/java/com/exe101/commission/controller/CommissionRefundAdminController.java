package com.exe101.commission.controller;

import com.exe101.commission.dto.CommissionRefundDTO;
import com.exe101.commission.dto.DecideCommissionRefundDTO;
import com.exe101.commission.mapper.CommissionRefundMapper;
import com.exe101.commission.service.CommissionRefundService;
import com.exe101.refund.model.RefundStatus;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The transfer of an approved Commission refund is recorded through POST /admin/refund-transfers/{id}/status.
@RestController
@RequestMapping("/admin/commission-refund-requests")
@RequiredArgsConstructor
public class CommissionRefundAdminController {
    private final CommissionRefundService refundService;
    private final CommissionRefundMapper mapper;

    @GetMapping
    public List<CommissionRefundDTO> list(@RequestParam(required = false) RefundStatus status) {
        var refunds = refundService.listForStaff(status);
        var response = mapper.toDto(refunds);

        return response;
    }

    @PostMapping("/{refundRequestId}/decision")
    public CommissionRefundDTO decide(
        @PathVariable Long refundRequestId, @RequestBody @Valid DecideCommissionRefundDTO dto,
        @AuthenticationPrincipal Long staffId
    ) {
        var decision = mapper.toModel(dto);
        var decided = refundService.decide(staffId, refundRequestId, decision);
        var response = mapper.toDto(decided);

        return response;
    }
}
