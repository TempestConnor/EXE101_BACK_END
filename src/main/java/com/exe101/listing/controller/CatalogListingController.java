package com.exe101.listing.controller;

import com.exe101.listing.dto.CatalogListingDTO;
import com.exe101.listing.mapper.ListingMapper;
import com.exe101.listing.service.ListingService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/catalog/artworks/{artworkId}/listings")
@RequiredArgsConstructor
public class CatalogListingController {
    private final ListingService listingService;
    private final ListingMapper mapper;

    @GetMapping
    public List<CatalogListingDTO> listPublished(@PathVariable Long artworkId) {
        var listings = listingService.listPublished(artworkId);
        var response = mapper.toCatalogDto(listings);

        return response;
    }
}
