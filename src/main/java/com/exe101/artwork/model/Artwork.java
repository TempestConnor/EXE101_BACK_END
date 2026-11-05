package com.exe101.artwork.model;

import com.exe101.tag.model.Tag;
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
public class Artwork {
    private Long artworkId;
    private Long artistId;
    // The Artist's current display name, read with the Artwork for every listing (added 5 November 2026).
    private String artistDisplayName;
    private String title;
    private String description;
    private String dimension;
    private ArtworkStatus status;
    private OffsetDateTime publishedAt;
    private OffsetDateTime createdAt;
    private byte[] version;
    private boolean purchasable;
    private List<Tag> tags;
    private List<ArtworkPreview> previews;
}
