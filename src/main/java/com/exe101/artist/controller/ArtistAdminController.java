package com.exe101.artist.controller;

import com.exe101.artist.dto.ArtistDeactivationDTO;
import com.exe101.artist.dto.ArtistReasonDTO;
import com.exe101.artist.mapper.ArtistMapper;
import com.exe101.artist.service.ArtistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/artists")
@RequiredArgsConstructor
public class ArtistAdminController {
    private final ArtistService artistService;
    private final ArtistMapper mapper;

    @PostMapping("/{artistId}/deactivation")
    public ArtistDeactivationDTO deactivate(
        @PathVariable Long artistId, @RequestBody @Valid ArtistReasonDTO dto, @AuthenticationPrincipal Long staffId
    ) {
        var deactivation = artistService.deactivate(staffId, artistId, dto.reason());
        var response = mapper.toDto(deactivation);

        return response;
    }

    @PostMapping("/{artistId}/reinstatement")
    public ArtistDeactivationDTO reinstate(
        @PathVariable Long artistId, @RequestBody @Valid ArtistReasonDTO dto, @AuthenticationPrincipal Long staffId
    ) {
        var deactivation = artistService.reinstate(staffId, artistId, dto.reason());
        var response = mapper.toDto(deactivation);

        return response;
    }
}
