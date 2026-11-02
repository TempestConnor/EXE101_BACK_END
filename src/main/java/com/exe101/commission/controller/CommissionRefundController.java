package com.exe101.commission.controller;

import com.exe101.commission.dto.CommissionRefundDTO;
import com.exe101.commission.dto.ObjectRefundDTO;
import com.exe101.commission.dto.SetRefundPercentDTO;
import com.exe101.commission.mapper.CommissionRefundMapper;
import com.exe101.commission.service.CommissionRefundService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// A paid Commission's refund is opened by its cancellation; the participants only answer it.
@RestController
@RequestMapping("/commissions/{commissionId}/refund-requests")
@RequiredArgsConstructor
public class CommissionRefundController {
    private final CommissionRefundService refundService;
    private final CommissionRefundMapper mapper;

    @GetMapping
    public List<CommissionRefundDTO> list(@PathVariable Long commissionId, @AuthenticationPrincipal Long userId) {
        var refunds = refundService.list(userId, commissionId);
        var response = mapper.toDto(refunds);

        return response;
    }

    @PostMapping("/{refundRequestId}/percentage")
    public CommissionRefundDTO setPercent(
        @PathVariable Long commissionId, @PathVariable Long refundRequestId,
        @RequestBody @Valid SetRefundPercentDTO dto, @AuthenticationPrincipal Long userId
    ) {
        var updated = refundService.setPercent(userId, commissionId, refundRequestId, dto.percent());
        var response = mapper.toDto(updated);

        return response;
    }

    @PostMapping("/{refundRequestId}/accept")
    public CommissionRefundDTO accept(
        @PathVariable Long commissionId, @PathVariable Long refundRequestId, @AuthenticationPrincipal Long userId
    ) {
        var accepted = refundService.accept(userId, commissionId, refundRequestId);
        var response = mapper.toDto(accepted);

        return response;
    }

    @PostMapping("/{refundRequestId}/objection")
    public CommissionRefundDTO object(
        @PathVariable Long commissionId, @PathVariable Long refundRequestId, @RequestBody @Valid ObjectRefundDTO dto,
        @AuthenticationPrincipal Long userId
    ) {
        var objected = refundService.object(userId, commissionId, refundRequestId, dto.reason());
        var response = mapper.toDto(objected);

        return response;
    }
}
