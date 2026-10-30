package com.exe101.listing.controller;

import com.exe101.listing.dto.ChangeListingStatusDTO;
import com.exe101.listing.dto.CreateListingDTO;
import com.exe101.listing.dto.ListingDTO;
import com.exe101.listing.dto.ManufacturingProductDTO;
import com.exe101.listing.dto.UpdateListingDTO;
import com.exe101.listing.mapper.ListingMapper;
import com.exe101.listing.service.ListingService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/listings")
@RequiredArgsConstructor
public class ListingController {
    private final ListingService listingService;
    private final ListingMapper mapper;

    @GetMapping("/products")
    public List<ManufacturingProductDTO> listProducts() {
        var products = listingService.listProducts();
        var response = mapper.toProductDto(products);

        return response;
    }

    @GetMapping
    public List<ListingDTO> listOwn(@RequestParam Long artworkId, @AuthenticationPrincipal Long userId) {
        var listings = listingService.listOwn(userId, artworkId);
        var response = mapper.toDto(listings);

        return response;
    }

    @GetMapping("/{listingId}")
    public ListingDTO getOwn(@PathVariable Long listingId, @AuthenticationPrincipal Long userId) {
        var listing = listingService.getOwn(userId, listingId);
        var response = mapper.toDto(listing);

        return response;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ListingDTO create(@RequestBody @Valid CreateListingDTO dto, @AuthenticationPrincipal Long userId) {
        var details = mapper.toModel(dto);
        var created = listingService.create(userId, details);
        var response = mapper.toDto(created);

        return response;
    }

    @PutMapping("/{listingId}")
    public ListingDTO update(
        @PathVariable Long listingId, @RequestBody @Valid UpdateListingDTO dto, @AuthenticationPrincipal Long userId
    ) {
        var details = mapper.toModel(dto);
        var updated = listingService.update(userId, listingId, details);
        var response = mapper.toDto(updated);

        return response;
    }

    @PutMapping("/{listingId}/status")
    public ListingDTO changeStatus(
        @PathVariable Long listingId, @RequestBody @Valid ChangeListingStatusDTO dto,
        @AuthenticationPrincipal Long userId
    ) {
        var change = mapper.toModel(dto);
        var updated = listingService.changeStatus(userId, listingId, change);
        var response = mapper.toDto(updated);

        return response;
    }

    @PostMapping(path = "/{listingId}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ListingDTO addAsset(
        @PathVariable Long listingId, @RequestPart("file") MultipartFile file, @AuthenticationPrincipal Long userId
    ) {
        var upload = mapper.toModel(file);
        var updated = listingService.addAsset(userId, listingId, upload);
        var response = mapper.toDto(updated);

        return response;
    }

    @DeleteMapping("/{listingId}/assets/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAsset(
        @PathVariable Long listingId, @PathVariable Long assetId, @AuthenticationPrincipal Long userId
    ) {
        listingService.removeAsset(userId, listingId, assetId);
    }

    @GetMapping("/{listingId}/assets/{assetId}/content")
    public ResponseEntity<byte[]> getAssetContent(
        @PathVariable Long listingId, @PathVariable Long assetId, @AuthenticationPrincipal Long userId
    ) {
        var content = listingService.getOwnAsset(userId, listingId, assetId);
        var response = ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.getContentType()))
            .body(content.getBytes());

        return response;
    }
}
