package com.exe101.artist.controller;

import com.exe101.artist.dto.ArtistTermsDTO;
import com.exe101.artist.dto.PublishTermsDTO;
import com.exe101.artist.mapper.ArtistMapper;
import com.exe101.artist.service.ArtistTermsService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/artists")
@RequiredArgsConstructor
public class ArtistTermsController {
    private final ArtistTermsService termsService;
    private final ArtistMapper mapper;

    // A new version: a "request" JSON part with the text and the kept images, and up to ten "images" parts.
    @PostMapping(path = "/me/terms", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ArtistTermsDTO publish(
        @RequestPart("request") @Valid PublishTermsDTO dto,
        @RequestPart(value = "images", required = false) List<MultipartFile> images,
        @AuthenticationPrincipal Long userId
    ) {
        var publication = mapper.toModel(dto, images);
        var published = termsService.publish(userId, publication);
        var response = mapper.toDto(published);

        return response;
    }

    @GetMapping("/{artistId}/terms")
    public ArtistTermsDTO getCurrent(@PathVariable Long artistId) {
        var terms = termsService.getCurrent(artistId);
        var response = mapper.toDto(terms);

        return response;
    }

    @GetMapping("/{artistId}/terms/images/{imageId}/content")
    public ResponseEntity<Resource> getImageContent(@PathVariable Long artistId, @PathVariable Long imageId) {
        var content = termsService.getImage(artistId, imageId);
        var response = ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.getContentType()))
            .contentLength(content.getByteLength())
            .body(content.getContent());

        return response;
    }
}
