package com.exe101.artwork.controller;

import com.exe101.artwork.dto.ArtworkDTO;
import com.exe101.artwork.mapper.ArtworkMapper;
import com.exe101.artwork.service.ArtworkService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/catalog/artworks")
@RequiredArgsConstructor
public class CatalogController {
    private final ArtworkService artworkService;
    private final ArtworkMapper mapper;

    @GetMapping
    public List<ArtworkDTO> listPublished() {
        var artworks = artworkService.listPublished();
        var response = mapper.toDto(artworks);

        return response;
    }

    @GetMapping("/{artworkId}")
    public ArtworkDTO getPublished(@PathVariable Long artworkId) {
        var artwork = artworkService.getPublished(artworkId);
        var response = mapper.toDto(artwork);

        return response;
    }

    @GetMapping("/{artworkId}/previews/{mediaId}/content")
    public ResponseEntity<byte[]> getPreviewContent(@PathVariable Long artworkId, @PathVariable Long mediaId) {
        var content = artworkService.getPublishedPreview(artworkId, mediaId);
        var response = ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.getContentType()))
            .body(content.getBytes());

        return response;
    }
}
