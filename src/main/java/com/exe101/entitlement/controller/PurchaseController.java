package com.exe101.entitlement.controller;

import com.exe101.entitlement.dto.PurchaseDTO;
import com.exe101.entitlement.mapper.PurchaseMapper;
import com.exe101.entitlement.service.EntitlementService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/purchases")
@RequiredArgsConstructor
public class PurchaseController {
    private final EntitlementService entitlementService;
    private final PurchaseMapper mapper;

    @GetMapping
    public List<PurchaseDTO> listOwn(@AuthenticationPrincipal Long userId) {
        var purchases = entitlementService.listOwn(userId);
        var response = mapper.toDto(purchases);

        return response;
    }
}
