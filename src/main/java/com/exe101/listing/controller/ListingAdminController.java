package com.exe101.listing.controller;

import com.exe101.listing.dto.ChangeListingStatusDTO;
import com.exe101.listing.dto.ListingDTO;
import com.exe101.listing.mapper.ListingMapper;
import com.exe101.listing.service.ListingService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/listings")
@RequiredArgsConstructor
public class ListingAdminController {
    private final ListingService listingService;
    private final ListingMapper mapper;

    @GetMapping
    public List<ListingDTO> listForArtwork(@RequestParam Long artworkId) {
        var listings = listingService.listForArtwork(artworkId);
        var response = mapper.toDto(listings);

        return response;
    }

    @GetMapping("/{listingId}")
    public ListingDTO get(@PathVariable Long listingId) {
        var listing = listingService.get(listingId);
        var response = mapper.toDto(listing);

        return response;
    }

    @PutMapping("/{listingId}/status")
    public ListingDTO changeStatus(
        @PathVariable Long listingId, @RequestBody @Valid ChangeListingStatusDTO dto,
        @AuthenticationPrincipal Long staffId
    ) {
        var change = mapper.toModel(dto);
        var updated = listingService.changeStatusAsAdmin(staffId, listingId, change);
        var response = mapper.toDto(updated);

        return response;
    }

    @GetMapping("/{listingId}/assets/{assetId}/content")
    public ResponseEntity<Resource> getAssetContent(@PathVariable Long listingId, @PathVariable Long assetId) {
        var content = listingService.getAsset(listingId, assetId);
        var response = ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.getContentType()))
            .contentLength(content.getByteLength())
            .body(content.getContent());

        return response;
    }
}
