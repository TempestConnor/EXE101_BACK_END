package com.exe101.listing.config;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Demo manufacturing products; the real costs are not yet known.
@ConfigurationProperties("app.manufacturing")
public record ManufacturingProperties(List<Product> products) {
    public record Product(String code, String name, String specifications, BigDecimal cost) {
    }
}
