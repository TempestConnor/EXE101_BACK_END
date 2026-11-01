package com.exe101.fulfillment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// The return window after delivery (amendment §C.9) and how often closed windows are looked for (approved
// 1 November 2026).
@ConfigurationProperties("app.fulfillment")
public record FulfillmentProperties(Duration returnWindow, Duration grantInterval) {
    public FulfillmentProperties {
        boolean validWindow = returnWindow != null && returnWindow.isPositive();
        boolean validInterval = grantInterval != null && grantInterval.isPositive();

        if (!validWindow || !validInterval) {
            throw new IllegalStateException("Invalid fulfillment configuration (app.fulfillment)");
        }
    }
}
