package com.exe101.artwork.controller;

import com.exe101.artwork.dto.ArtworkDTO;
import com.exe101.artwork.dto.ChangeArtworkStatusDTO;
import com.exe101.artwork.mapper.ArtworkMapper;
import com.exe101.artwork.service.ArtworkService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/artworks")
@RequiredArgsConstructor
public class ArtworkAdminController {
    private final ArtworkService artworkService;
    private final ArtworkMapper mapper;

    @GetMapping
    public List<ArtworkDTO> listAll() {
        var artworks = artworkService.listAll();
        var response = mapper.toDto(artworks);

        return response;
    }

    @GetMapping("/{artworkId}")
    public ArtworkDTO get(@PathVariable Long artworkId) {
        var artwork = artworkService.get(artworkId);
        var response = mapper.toDto(artwork);

        return response;
    }

    @PutMapping("/{artworkId}/status")
    public ArtworkDTO changeStatus(
        @PathVariable Long artworkId, @RequestBody @Valid ChangeArtworkStatusDTO dto,
        @AuthenticationPrincipal Long staffId
    ) {
        var change = mapper.toModel(dto);
        var updated = artworkService.changeStatusAsAdmin(staffId, artworkId, change);
        var response = mapper.toDto(updated);

        return response;
    }

    @GetMapping("/{artworkId}/previews/{mediaId}/content")
    public ResponseEntity<byte[]> getPreviewContent(@PathVariable Long artworkId, @PathVariable Long mediaId) {
        var content = artworkService.getPreview(artworkId, mediaId);
        var response = ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.getContentType()))
            .body(content.getBytes());

        return response;
    }
}
