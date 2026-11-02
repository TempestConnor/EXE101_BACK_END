package com.exe101.artist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

// keepImageIds: images of the current terms carried into the new version, in order, before the uploaded ones.
public record PublishTermsDTO(@NotBlank @Size(max = 20000) String text, List<Long> keepImageIds) {
}
