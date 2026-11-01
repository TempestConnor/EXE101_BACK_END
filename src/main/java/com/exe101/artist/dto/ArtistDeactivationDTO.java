package com.exe101.artist.dto;

import java.time.OffsetDateTime;

public record ArtistDeactivationDTO(
    Long deactivationId, Long artistId, String artistStatus, OffsetDateTime startedAt, OffsetDateTime closedAt,
    String reason, Long staffId
) {
}
