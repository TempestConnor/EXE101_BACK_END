package com.exe101.staff.security;

import com.exe101.common.security.JwtProperties;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.staff.config.StaffTokenConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

// Staff tokens share the signing key and the lifetime of Customer tokens.
class StaffTokenTest {
    private Instant now = Instant.parse("2026-11-05T00:00:00Z");

    @Test
    void issued_token_lifetime_ok() {
        // given
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var customerConfig = new CustomerTokenConfiguration();
        var key = customerConfig.customerSigningKey(
            "dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1taW5pbXVtLW5vdC1mb3ItZGVwbG95bWVudA==");
        var decoder = new StaffTokenConfiguration().staffJwtDecoder(key, clock);
        var issuer = new StaffTokenIssuer(customerConfig.customerJwtEncoder(key), clock,
            new JwtProperties(Duration.ofHours(2)));

        // when
        var token = issuer.issue(7L);

        // then
        var decoded = decoder.decode(token.getAccessToken());
        assertAll(
            () -> assertThat(decoded.getSubject()).isEqualTo("7"),
            () -> assertThat(decoded.getExpiresAt()).isEqualTo(now.plusSeconds(7200)),
            () -> assertThat(token.getExpiresIn()).isEqualTo(7200)
        );
    }
}
