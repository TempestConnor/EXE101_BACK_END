package com.exe101.listing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangeListingStatusDTO(
    @NotNull @Pattern(regexp = "DRAFT|PUBLISHED|UNLISTED|MODERATED|ARCHIVED") String status,
    @Size(max = 2000) String reason
) {
}
