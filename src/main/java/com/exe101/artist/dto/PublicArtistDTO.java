package com.exe101.artist.dto;

import java.math.BigDecimal;

public record PublicArtistDTO(
    Long artistId, String displayName, String bio, String status, boolean acceptingCommissions,
    BigDecimal startingPrice
) {
}
