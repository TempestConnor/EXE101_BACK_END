package com.exe101.commission.model;

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
public class CommissionRequest {
    private Long artistId;
    private String description;
    private boolean allowPublicDisplay;
    private List<FileUpload> uploads;
}
