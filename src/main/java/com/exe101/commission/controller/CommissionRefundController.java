package com.exe101.commission.controller;

import com.exe101.commission.dto.CommissionRefundDTO;
import com.exe101.commission.mapper.CommissionRefundMapper;
import com.exe101.commission.service.CommissionRefundService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// A paid Commission's refund is offered and agreed through its cancellation requests; this lists the offers and
// their outcomes.
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
}
