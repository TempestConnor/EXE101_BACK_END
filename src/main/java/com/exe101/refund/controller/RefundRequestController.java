package com.exe101.refund.controller;

import com.exe101.refund.dto.CreateRefundRequestDTO;
import com.exe101.refund.dto.RefundRequestDTO;
import com.exe101.refund.mapper.RefundMapper;
import com.exe101.refund.service.RefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// A refund request belongs to one item of the buyer's Order, so it lives under that item's path.
@RestController
@RequestMapping("/orders/{orderId}/items/{orderItemId}/refund-requests")
@RequiredArgsConstructor
public class RefundRequestController {
    private final RefundService refundService;
    private final RefundMapper mapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RefundRequestDTO request(
        @PathVariable Long orderId, @PathVariable Long orderItemId, @RequestBody @Valid CreateRefundRequestDTO dto,
        @AuthenticationPrincipal Long userId
    ) {
        var created = refundService.request(userId, orderId, orderItemId, dto.reason());
        var response = mapper.toDto(created);

        return response;
    }
}
