package com.exe101.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Payment window of a new Order and how often overdue Orders are expired (approved 31 October 2026).
@ConfigurationProperties("app.orders")
public record OrderProperties(Duration paymentWindow, Duration expiryInterval) {
    public OrderProperties {
        boolean validWindow = paymentWindow != null && paymentWindow.isPositive();
        boolean validInterval = expiryInterval != null && expiryInterval.isPositive();

        if (!validWindow || !validInterval) {
            throw new IllegalStateException("Invalid order configuration (app.orders)");
        }
    }
}
