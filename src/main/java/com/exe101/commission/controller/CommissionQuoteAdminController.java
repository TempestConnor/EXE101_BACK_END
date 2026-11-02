package com.exe101.commission.controller;

import com.exe101.artist.dto.ArtistTermsDTO;
import com.exe101.artist.mapper.ArtistMapper;
import com.exe101.commission.service.CommissionQuoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/commissions/{commissionId}/quotes")
@RequiredArgsConstructor
public class CommissionQuoteAdminController {
    private final CommissionQuoteService quoteService;
    private final ArtistMapper artistMapper;

    @GetMapping("/{quoteId}/terms")
    public ArtistTermsDTO getAcceptedTerms(@PathVariable Long commissionId, @PathVariable Long quoteId) {
        var terms = quoteService.getAcceptedTermsAsAdmin(commissionId, quoteId);
        var response = artistMapper.toDto(terms);

        return response;
    }
}
