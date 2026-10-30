package com.exe101.province.model;

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
public class Province {
    private Long provinceId;
    private String provinceCode;
    private String provinceName;
    private boolean supported;
    private OffsetDateTime updatedAt;
}
