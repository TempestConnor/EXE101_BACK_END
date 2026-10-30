package com.exe101.artwork.model;

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
public class ArtworkDetails {
    private String title;
    private String description;
    private String dimension;
    private List<Integer> tagIds;
    private byte[] version;
}
