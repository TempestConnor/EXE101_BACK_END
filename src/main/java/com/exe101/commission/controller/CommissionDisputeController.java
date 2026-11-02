package com.exe101.commission.controller;

import com.exe101.commission.dto.CommissionDisputeDTO;
import com.exe101.commission.dto.OpenDisputeDTO;
import com.exe101.commission.mapper.CommissionCancellationMapper;
import com.exe101.commission.service.CommissionDisputeService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/commissions/{commissionId}/disputes")
@RequiredArgsConstructor
public class CommissionDisputeController {
    private final CommissionDisputeService disputeService;
    private final CommissionCancellationMapper mapper;

    @GetMapping
    public List<CommissionDisputeDTO> list(@PathVariable Long commissionId, @AuthenticationPrincipal Long userId) {
        var disputes = disputeService.list(userId, commissionId);
        var response = mapper.toDisputeDtos(disputes);

        return response;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommissionDisputeDTO open(
        @PathVariable Long commissionId, @RequestBody @Valid OpenDisputeDTO dto, @AuthenticationPrincipal Long userId
    ) {
        var opened = disputeService.open(userId, commissionId, dto.reason());
        var response = mapper.toDto(opened);

        return response;
    }
}
