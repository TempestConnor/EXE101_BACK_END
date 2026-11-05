package com.exe101.common.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// How long a Customer or staff access token is valid; both use the same lifetime (decided 5 November 2026, 2 hours
// by default). Account status is still checked on every request, so blocking takes effect at once; a leaked token
// stays usable until it expires, and logging out does not revoke it.
@ConfigurationProperties("app.jwt")
public record JwtProperties(Duration accessTokenLifetime) {
    public JwtProperties {
        if (accessTokenLifetime == null || !accessTokenLifetime.isPositive()) {
            throw new IllegalStateException("Invalid token configuration (app.jwt.access-token-lifetime)");
        }
    }
}
