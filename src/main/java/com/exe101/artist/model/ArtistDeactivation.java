package com.exe101.artist.model;

import java.time.OffsetDateTime;
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
public class ArtistDeactivation {
    private Long deactivationId;
    private Long artistId;
    private String artistStatus;
    private OffsetDateTime startedAt;
    private OffsetDateTime closedAt;
    private String reason;
    private Long staffId;
}
