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
public class Artist {
    private Long artistId;
    private Long userId;
    private String displayName;
    private String status;
    private boolean acceptingCommissions;
    private Long authorizedByStaffId;
    private OffsetDateTime createdAt;
}
