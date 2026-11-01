package com.exe101.refund.controller;

import com.exe101.refund.dto.RefundRequestDTO;
import com.exe101.refund.mapper.RefundMapper;
import com.exe101.refund.service.RefundService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/refund-requests")
@RequiredArgsConstructor
public class RefundController {
    private final RefundService refundService;
    private final RefundMapper mapper;

    @GetMapping
    public List<RefundRequestDTO> listOwn(@AuthenticationPrincipal Long userId) {
        var refunds = refundService.listOwn(userId);
        var response = mapper.toDto(refunds);

        return response;
    }
}
