package com.exe101.artist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record GrantArtistDTO(
    @NotNull @Positive Long userId,
    @NotBlank @Size(max = 160) String displayName,
    @Size(max = 2000) String reason
) {
}
