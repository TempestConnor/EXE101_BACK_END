package com.exe101.artwork.controller;

import com.exe101.artwork.dto.ArtworkDTO;
import com.exe101.artwork.dto.ChangeArtworkStatusDTO;
import com.exe101.artwork.dto.CreateArtworkDTO;
import com.exe101.artwork.dto.MovePreviewDTO;
import com.exe101.artwork.dto.UpdateArtworkDTO;
import com.exe101.artwork.mapper.ArtworkMapper;
import com.exe101.artwork.service.ArtworkService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/artworks")
@RequiredArgsConstructor
public class ArtworkController {
    private final ArtworkService artworkService;
    private final ArtworkMapper mapper;

    @GetMapping
    public List<ArtworkDTO> listOwn(@AuthenticationPrincipal Long userId) {
        var artworks = artworkService.listOwn(userId);
        var response = mapper.toDto(artworks);

        return response;
    }

    @GetMapping("/{artworkId}")
    public ArtworkDTO getOwn(@PathVariable Long artworkId, @AuthenticationPrincipal Long userId) {
        var artwork = artworkService.getOwn(userId, artworkId);
        var response = mapper.toDto(artwork);

        return response;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ArtworkDTO create(@RequestBody @Valid CreateArtworkDTO dto, @AuthenticationPrincipal Long userId) {
        var details = mapper.toModel(dto);
        var created = artworkService.create(userId, details);
        var response = mapper.toDto(created);

        return response;
    }

    @PutMapping("/{artworkId}")
    public ArtworkDTO update(
        @PathVariable Long artworkId, @RequestBody @Valid UpdateArtworkDTO dto, @AuthenticationPrincipal Long userId
    ) {
        var details = mapper.toModel(dto);
        var updated = artworkService.update(userId, artworkId, details);
        var response = mapper.toDto(updated);

        return response;
    }

    @PutMapping("/{artworkId}/status")
    public ArtworkDTO changeStatus(
        @PathVariable Long artworkId, @RequestBody @Valid ChangeArtworkStatusDTO dto,
        @AuthenticationPrincipal Long userId
    ) {
        var change = mapper.toModel(dto);
        var updated = artworkService.changeStatus(userId, artworkId, change);
        var response = mapper.toDto(updated);

        return response;
    }

    @PostMapping(path = "/{artworkId}/previews", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ArtworkDTO addPreview(
        @PathVariable Long artworkId, @RequestPart("file") MultipartFile file,
        @RequestParam(required = false) Integer sortOrder, @AuthenticationPrincipal Long userId
    ) {
        var upload = mapper.toModel(file, sortOrder);
        var updated = artworkService.addPreview(userId, artworkId, upload);
        var response = mapper.toDto(updated);

        return response;
    }

    @PutMapping("/{artworkId}/previews/{mediaId}")
    public ArtworkDTO movePreview(
        @PathVariable Long artworkId, @PathVariable Long mediaId, @RequestBody @Valid MovePreviewDTO dto,
        @AuthenticationPrincipal Long userId
    ) {
        var move = mapper.toModel(mediaId, dto);
        var updated = artworkService.movePreview(userId, artworkId, move);
        var response = mapper.toDto(updated);

        return response;
    }

    @DeleteMapping("/{artworkId}/previews/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePreview(
        @PathVariable Long artworkId, @PathVariable Long mediaId, @AuthenticationPrincipal Long userId
    ) {
        artworkService.removePreview(userId, artworkId, mediaId);
    }

    @GetMapping("/{artworkId}/previews/{mediaId}/content")
    public ResponseEntity<Resource> getPreviewContent(
        @PathVariable Long artworkId, @PathVariable Long mediaId, @AuthenticationPrincipal Long userId
    ) {
        var content = artworkService.getOwnPreview(userId, artworkId, mediaId);
        var response = ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.getContentType()))
            .contentLength(content.getByteLength())
            .body(content.getContent());

        return response;
    }
}
