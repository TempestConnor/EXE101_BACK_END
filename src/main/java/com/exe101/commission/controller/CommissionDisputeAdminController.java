package com.exe101.commission.controller;

import com.exe101.commission.dto.CommissionDisputeDTO;
import com.exe101.commission.dto.DisputeEvidenceDTO;
import com.exe101.commission.dto.ResolveDisputeDTO;
import com.exe101.commission.mapper.CommissionCancellationMapper;
import com.exe101.commission.model.DisputeStatus;
import com.exe101.commission.service.CommissionDisputeService;
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
@RequestMapping("/admin/commission-disputes")
@RequiredArgsConstructor
public class CommissionDisputeAdminController {
    private final CommissionDisputeService disputeService;
    private final CommissionCancellationMapper mapper;

    @GetMapping
    public List<CommissionDisputeDTO> list(@RequestParam(required = false) DisputeStatus status) {
        var disputes = disputeService.listForStaff(status);
        var response = mapper.toDisputeDtos(disputes);

        return response;
    }

    @GetMapping("/{disputeId}")
    public DisputeEvidenceDTO get(@PathVariable Long disputeId) {
        var evidence = disputeService.evidence(disputeId);
        var response = mapper.toDto(evidence);

        return response;
    }

    @PostMapping("/{disputeId}/review")
    public CommissionDisputeDTO review(@PathVariable Long disputeId, @AuthenticationPrincipal Long staffId) {
        var reviewed = disputeService.review(staffId, disputeId);
        var response = mapper.toDto(reviewed);

        return response;
    }

    @PostMapping("/{disputeId}/resolution")
    public CommissionDisputeDTO resolve(
        @PathVariable Long disputeId, @RequestBody @Valid ResolveDisputeDTO dto, @AuthenticationPrincipal Long staffId
    ) {
        var resolution = mapper.toModel(dto);
        var resolved = disputeService.resolve(staffId, disputeId, resolution);
        var response = mapper.toDto(resolved);

        return response;
    }
}
