package com.exe101.payment.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

// VNPay merchant settings (provider approved 1 November 2026). The terminal code and hash secret come from the
// VNPay merchant portal; returnUrl is the storefront page VNPay sends the buyer back to.
@ConfigurationProperties("app.payments.vnpay")
public record VnpayProperties(String tmnCode, String hashSecret, URI payUrl, URI returnUrl) {
    public VnpayProperties {
        boolean validMerchant = tmnCode != null && !tmnCode.isBlank() && hashSecret != null && !hashSecret.isBlank();
        boolean validUrls = payUrl != null && payUrl.isAbsolute() && returnUrl != null && returnUrl.isAbsolute();

        if (!validMerchant || !validUrls) {
            throw new IllegalStateException("Invalid VNPay configuration (app.payments.vnpay)");
        }
    }
}
