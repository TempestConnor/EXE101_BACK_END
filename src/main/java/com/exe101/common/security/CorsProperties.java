package com.exe101.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Browser origins allowed to call the API from another origin, such as a storefront on its own domain. Empty, the
// default, allows none: a storefront served through the same origin (or a dev proxy) needs no entry. Each entry is
// an exact origin (scheme, host and port); a wildcard is refused, because it would let any site call the API.
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {
    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
            .map(String::strip)
            .filter(origin -> !origin.isEmpty())
            .toList();

        if (allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException("Invalid CORS configuration (app.cors.allowed-origins): no wildcards");
        }
    }
}
