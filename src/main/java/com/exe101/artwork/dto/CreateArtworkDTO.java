package com.exe101.artwork.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateArtworkDTO(
    @NotBlank @Size(max = 240) String title,
    String description,
    @NotNull @Pattern(regexp = "2D|3D") String dimension,
    List<@NotNull @Positive Integer> tagIds
) {
}
