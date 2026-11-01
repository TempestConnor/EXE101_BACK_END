package com.exe101.artist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ArtistReasonDTO(@NotBlank @Size(max = 2000) String reason) {
}
