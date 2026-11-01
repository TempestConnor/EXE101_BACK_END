package com.exe101.ledger.controller;

import com.exe101.ledger.dto.ReconciliationDTO;
import com.exe101.ledger.mapper.LedgerMapper;
import com.exe101.ledger.service.LedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/finance")
@RequiredArgsConstructor
public class LedgerAdminController {
    private final LedgerService ledgerService;
    private final LedgerMapper mapper;

    @GetMapping("/reconciliation")
    public ReconciliationDTO reconcile() {
        var reconciliation = ledgerService.reconcile();
        var response = mapper.toDto(reconciliation);

        return response;
    }
}
