package com.exe101.listing.dto;

import java.math.BigDecimal;

public record ManufacturingProductDTO(String code, String name, String specifications, BigDecimal cost) {
}
