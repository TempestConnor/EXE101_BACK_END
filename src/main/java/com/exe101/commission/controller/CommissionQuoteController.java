package com.exe101.commission.controller;

import com.exe101.artist.dto.ArtistTermsDTO;
import com.exe101.artist.mapper.ArtistMapper;
import com.exe101.commission.dto.AcceptQuoteDTO;
import com.exe101.commission.dto.CommissionQuoteDTO;
import com.exe101.commission.dto.CommissionSummaryDTO;
import com.exe101.commission.dto.OfferQuoteDTO;
import com.exe101.commission.mapper.CommissionMapper;
import com.exe101.commission.mapper.CommissionQuoteMapper;
import com.exe101.commission.service.CommissionQuoteService;
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
public class CommissionQuoteController {
    private final CommissionQuoteService quoteService;
    private final CommissionQuoteMapper mapper;
    private final CommissionMapper commissionMapper;
    private final ArtistMapper artistMapper;

    @GetMapping("/quotes")
    public List<CommissionQuoteDTO> list(@PathVariable Long commissionId, @AuthenticationPrincipal Long userId) {
        var quotes = quoteService.list(userId, commissionId);
        var response = mapper.toDtos(quotes);

        return response;
    }

    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    public CommissionQuoteDTO offer(
        @PathVariable Long commissionId, @RequestBody @Valid OfferQuoteDTO dto, @AuthenticationPrincipal Long userId
    ) {
        var terms = mapper.toModel(dto);
        var offered = quoteService.offer(userId, commissionId, terms);
        var response = mapper.toDto(offered);

        return response;
    }

    @PostMapping("/quotes/{quoteId}/accept")
    public CommissionQuoteDTO accept(
        @PathVariable Long commissionId, @PathVariable Long quoteId, @RequestBody @Valid AcceptQuoteDTO dto,
        @AuthenticationPrincipal Long userId
    ) {
        var accepted = quoteService.accept(userId, commissionId, quoteId, dto.artistTermsId());
        var response = mapper.toDto(accepted);

        return response;
    }

    @GetMapping("/quotes/{quoteId}/terms")
    public ArtistTermsDTO getAcceptedTerms(
        @PathVariable Long commissionId, @PathVariable Long quoteId, @AuthenticationPrincipal Long userId
    ) {
        var terms = quoteService.getAcceptedTerms(userId, commissionId, quoteId);
        var response = artistMapper.toDto(terms);

        return response;
    }

    @PostMapping("/quotes/{quoteId}/decline")
    public CommissionQuoteDTO decline(
        @PathVariable Long commissionId, @PathVariable Long quoteId, @AuthenticationPrincipal Long userId
    ) {
        var declined = quoteService.decline(userId, commissionId, quoteId);
        var response = mapper.toDto(declined);

        return response;
    }

    @PostMapping("/quotes/{quoteId}/withdraw")
    public CommissionQuoteDTO withdraw(
        @PathVariable Long commissionId, @PathVariable Long quoteId, @AuthenticationPrincipal Long userId
    ) {
        var withdrawn = quoteService.withdraw(userId, commissionId, quoteId);
        var response = mapper.toDto(withdrawn);

        return response;
    }

    @PostMapping("/decline")
    public CommissionSummaryDTO declineRequest(@PathVariable Long commissionId, @AuthenticationPrincipal Long userId) {
        var declined = quoteService.declineRequest(userId, commissionId);
        var response = commissionMapper.toSummaryDto(declined);

        return response;
    }
}
