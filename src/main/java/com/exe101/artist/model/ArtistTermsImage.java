package com.exe101.artist.model;

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
public class ArtistTermsImage {
    private Long imageId;
    private Long fileId;
    private int sortOrder;
    private String contentType;
    private long byteLength;
}
