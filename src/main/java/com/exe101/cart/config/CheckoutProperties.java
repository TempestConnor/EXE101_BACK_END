package com.exe101.cart.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Demo checkout values (approved 31 October 2026); the real tax rate and shipping charge are not yet known.
@ConfigurationProperties("app.checkout")
public record CheckoutProperties(BigDecimal taxRate, BigDecimal shippingFee, int maxPhysicalQuantity) {
    public CheckoutProperties {
        // Tax rate is a fraction (0.10 = 10%); shipping is whole VND below the decimal(19,4) limit.
        boolean validRate = taxRate != null && taxRate.signum() >= 0 && taxRate.compareTo(BigDecimal.ONE) < 0;
        boolean validFee = shippingFee != null && shippingFee.signum() >= 0
            && shippingFee.stripTrailingZeros().scale() <= 0 && shippingFee.compareTo(BigDecimal.TEN.pow(15)) < 0;

        if (!validRate || !validFee || maxPhysicalQuantity < 1) {
            throw new IllegalStateException("Invalid checkout configuration (app.checkout)");
        }
    }
}
