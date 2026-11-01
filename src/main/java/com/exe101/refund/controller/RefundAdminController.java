package com.exe101.refund.controller;

import com.exe101.refund.dto.DecideRefundDTO;
import com.exe101.refund.dto.RecordTransferOutcomeDTO;
import com.exe101.refund.dto.RefundRequestDTO;
import com.exe101.refund.mapper.RefundMapper;
import com.exe101.refund.model.RefundStatus;
import com.exe101.refund.service.RefundService;
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

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class RefundAdminController {
    private final RefundService refundService;
    private final RefundMapper mapper;

    @GetMapping("/refund-requests")
    public List<RefundRequestDTO> list(@RequestParam(required = false) RefundStatus status) {
        var refunds = refundService.list(status);
        var response = mapper.toDto(refunds);

        return response;
    }

    @PostMapping("/refund-requests/{refundRequestId}/decision")
    public RefundRequestDTO decide(
        @PathVariable Long refundRequestId, @RequestBody @Valid DecideRefundDTO dto,
        @AuthenticationPrincipal Long staffId
    ) {
        var decision = mapper.toModel(dto);
        var decided = refundService.decide(staffId, refundRequestId, decision);
        var response = mapper.toDto(decided);

        return response;
    }

    @PostMapping("/refund-transfers/{transferId}/status")
    public RefundRequestDTO recordTransferOutcome(
        @PathVariable Long transferId, @RequestBody @Valid RecordTransferOutcomeDTO dto,
        @AuthenticationPrincipal Long staffId
    ) {
        var outcome = mapper.toModel(dto);
        var updated = refundService.recordTransferOutcome(staffId, transferId, outcome);
        var response = mapper.toDto(updated);

        return response;
    }
}
