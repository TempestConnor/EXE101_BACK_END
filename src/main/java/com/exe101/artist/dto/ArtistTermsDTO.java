package com.exe101.artist.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record ArtistTermsDTO(
    Long artistTermsId, Long artistId, int versionNo, String text, OffsetDateTime publishedAt,
    List<ArtistTermsImageDTO> images
) {
}
