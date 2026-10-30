package com.exe101.artwork.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangeArtworkStatusDTO(
    @NotNull @Pattern(regexp = "DRAFT|PUBLISHED|UNLISTED|MODERATED|ARCHIVED") String status,
    @Size(max = 2000) String reason
) {
}
