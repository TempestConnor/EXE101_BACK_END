package com.exe101.province.dto;

import java.time.OffsetDateTime;

public record ProvinceDTO(
    Long provinceId, String provinceCode, String provinceName, boolean supported, OffsetDateTime updatedAt
) {
}
