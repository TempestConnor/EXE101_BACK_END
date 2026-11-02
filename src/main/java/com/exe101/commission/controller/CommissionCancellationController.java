package com.exe101.commission.controller;

import com.exe101.commission.dto.CancelCommissionDTO;
import com.exe101.commission.dto.CancellationRequestDTO;
import com.exe101.commission.dto.CommissionSummaryDTO;
import com.exe101.commission.dto.RespondCancellationDTO;
import com.exe101.commission.mapper.CommissionCancellationMapper;
import com.exe101.commission.mapper.CommissionMapper;
import com.exe101.commission.service.CommissionCancellationService;
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
@RequestMapping("/commissions/{commissionId}")
@RequiredArgsConstructor
public class CommissionCancellationController {
    private final CommissionCancellationService cancellationService;
    private final CommissionCancellationMapper mapper;
    private final CommissionMapper commissionMapper;

    // Before the first payment only; a paid Commission needs a cancellation request.
    @PostMapping("/cancel")
    public CommissionSummaryDTO cancel(
        @PathVariable Long commissionId, @RequestBody @Valid CancelCommissionDTO dto,
        @AuthenticationPrincipal Long userId
    ) {
        var cancelled = cancellationService.cancel(userId, commissionId, dto.reason());
        var response = commissionMapper.toSummaryDto(cancelled);

        return response;
    }

    @GetMapping("/cancellation-requests")
    public List<CancellationRequestDTO> list(@PathVariable Long commissionId, @AuthenticationPrincipal Long userId) {
        var requests = cancellationService.list(userId, commissionId);
        var response = mapper.toCancellationDtos(requests);

        return response;
    }

    @PostMapping("/cancellation-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public CancellationRequestDTO request(
        @PathVariable Long commissionId, @RequestBody @Valid CancelCommissionDTO dto,
        @AuthenticationPrincipal Long userId
    ) {
        var requested = cancellationService.request(userId, commissionId, dto.reason());
        var response = mapper.toDto(requested);

        return response;
    }

    @PostMapping("/cancellation-requests/{cancellationId}/accept")
    public CancellationRequestDTO accept(
        @PathVariable Long commissionId, @PathVariable Long cancellationId,
        @RequestBody(required = false) @Valid RespondCancellationDTO dto, @AuthenticationPrincipal Long userId
    ) {
        var accepted = cancellationService.accept(userId, commissionId, cancellationId, mapper.toNote(dto));
        var response = mapper.toDto(accepted);

        return response;
    }

    @PostMapping("/cancellation-requests/{cancellationId}/reject")
    public CancellationRequestDTO reject(
        @PathVariable Long commissionId, @PathVariable Long cancellationId,
        @RequestBody(required = false) @Valid RespondCancellationDTO dto, @AuthenticationPrincipal Long userId
    ) {
        var rejected = cancellationService.reject(userId, commissionId, cancellationId, mapper.toNote(dto));
        var response = mapper.toDto(rejected);

        return response;
    }

    @PostMapping("/cancellation-requests/{cancellationId}/withdraw")
    public CancellationRequestDTO withdraw(
        @PathVariable Long commissionId, @PathVariable Long cancellationId, @AuthenticationPrincipal Long userId
    ) {
        var withdrawn = cancellationService.withdraw(userId, commissionId, cancellationId);
        var response = mapper.toDto(withdrawn);

        return response;
    }
}
