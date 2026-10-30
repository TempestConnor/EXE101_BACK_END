package com.exe101.artwork.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record ArtworkDTO(
    Long artworkId, Long artistId, String title, String description, String dimension, String status,
    OffsetDateTime publishedAt, OffsetDateTime createdAt, String version, boolean purchasable,
    List<ArtworkTagDTO> tags, List<PreviewDTO> previews
) {
}
