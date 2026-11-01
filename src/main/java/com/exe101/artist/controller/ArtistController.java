package com.exe101.artist.controller;

import com.exe101.artist.dto.ArtistDTO;
import com.exe101.artist.dto.CommissionSettingsDTO;
import com.exe101.artist.dto.GrantArtistDTO;
import com.exe101.artist.dto.PublicArtistDTO;
import com.exe101.artist.mapper.ArtistMapper;
import com.exe101.artist.service.ArtistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/artists")
@RequiredArgsConstructor
public class ArtistController {
    private final ArtistService artistService;
    private final ArtistMapper mapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ArtistDTO grant(@RequestBody @Valid GrantArtistDTO dto, @AuthenticationPrincipal Long staffId) {
        var grant = mapper.toModel(dto);
        var created = artistService.grant(grant, staffId);
        var response = mapper.toDto(created);

        return response;
    }

    @GetMapping("/{artistId}")
    public PublicArtistDTO get(@PathVariable Long artistId) {
        var artist = artistService.getPublic(artistId);
        var response = mapper.toPublicDto(artist);

        return response;
    }

    @PutMapping("/me/commission-settings")
    public ArtistDTO updateCommissionSettings(
        @RequestBody @Valid CommissionSettingsDTO dto, @AuthenticationPrincipal Long userId
    ) {
        var settings = mapper.toModel(dto);
        var updated = artistService.updateCommissionSettings(userId, settings);
        var response = mapper.toDto(updated);

        return response;
    }
}
