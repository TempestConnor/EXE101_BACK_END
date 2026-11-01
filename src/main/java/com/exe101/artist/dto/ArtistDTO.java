package com.exe101.artist.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ArtistDTO(
    Long artistId, Long userId, String displayName, String status, boolean acceptingCommissions,
    BigDecimal startingPrice, OffsetDateTime createdAt
) {
}
