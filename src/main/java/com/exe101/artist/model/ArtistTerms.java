package com.exe101.artist.model;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class ArtistTerms {
    private Long artistTermsId;
    private Long artistId;
    private int versionNo;
    private String text;
    private OffsetDateTime publishedAt;
    private List<ArtistTermsImage> images;
}
