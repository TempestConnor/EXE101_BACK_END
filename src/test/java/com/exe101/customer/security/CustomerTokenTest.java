package com.exe101.customer.security;

import com.exe101.customer.config.CustomerTokenConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class CustomerTokenTest {
    private JwtEncoder encoder;
    private JwtDecoder decoder;
    private CustomerTokenIssuer issuer;
    private Instant now = Instant.parse("2026-09-27T00:00:00Z");

    @BeforeEach
    void setUp() {
        var config = new CustomerTokenConfiguration();
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var key = config.customerSigningKey("dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1taW5pbXVtLW5vdC1mb3ItZGVwbG95bWVudA==");
        encoder = config.customerJwtEncoder(key);
        decoder = config.customerJwtDecoder(key, clock);
        issuer = new CustomerTokenIssuer(encoder, clock);
    }

    @Test
    void issued_token_has_identity_expiry_and_customer_scope_ok() {
        // given / when
        var token = issuer.issue(42L);
        var decoded = decoder.decode(token.getAccessToken());

        // then
        assertAll(
            () -> assertThat(decoded.getSubject()).isEqualTo("42"),
            () -> assertThat(decoded.getExpiresAt()).isEqualTo(now.plusSeconds(900)),
            () -> assertThat(decoded.getClaimAsString("type")).isEqualTo("access"),
            () -> assertThat(decoded.getClaims()).doesNotContainKeys("email", "password", "roles"),
            () -> assertThat(token.getTokenType()).isEqualTo("Bearer")
        );
    }

    @Test
    void expired_token_ko() {
        // given
        var token = sign(claims().issuedAt(now.minusSeconds(900)).expiresAt(now).build());

        // when / then
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void missing_expiry_ko() {
        // given
        var token = sign(JwtClaimsSet.builder().issuer(CustomerTokenIssuer.issuer)
            .audience(List.of(CustomerTokenIssuer.audience)).subject("42").issuedAt(now)
            .claim("type", "access").build());

        // when / then
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void refresh_type_ko() {
        // given
        var token = sign(claims().claim("type", "refresh").build());

        // when / then
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void wrong_issuer_ko() {
        // given
        var token = sign(claims().issuer("staff").build());

        // when / then
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void wrong_audience_ko() {
        // given
        var token = sign(claims().audience(List.of("staff-api")).build());

        // when / then
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void invalid_subject_ko() {
        // given
        var token = sign(claims().subject("not-an-id").build());

        // when / then
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void future_token_ko() {
        // given
        var token = sign(claims().issuedAt(now.plusSeconds(1)).build());

        // when / then
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tampered_signature_ko() {
        // given
        var token = issuer.issue(42L).getAccessToken();
        var segments = token.split("\\.");
        var replacement = segments[2].startsWith("A") ? "B" : "A";
        var tampered = segments[0] + "." + segments[1] + "." + replacement + segments[2].substring(1);

        // when / then
        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void weak_signing_key_ko() {
        // given
        var config = new CustomerTokenConfiguration();

        // when / then
        assertThatThrownBy(() -> config.customerSigningKey("c2hvcnQ="))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passwords_support_long_unicode_values_without_truncation_ok() {
        // given
        var encoder = new CustomerTokenConfiguration().passwordEncoder();
        var password = "ệ".repeat(128);

        // when
        var encoded = encoder.encode(password);

        // then
        assertAll(
            () -> assertThat(encoder.matches(password, encoded)).isTrue(),
            () -> assertThat(encoder.matches("ệ".repeat(127) + "x", encoded)).isFalse(),
            () -> assertThat(encoded).doesNotContain(password),
            () -> assertThat(encoder.encode(password)).isNotEqualTo(encoded)
        );
    }

    private JwtClaimsSet.Builder claims() {
        return JwtClaimsSet.builder().issuer(CustomerTokenIssuer.issuer)
            .audience(List.of(CustomerTokenIssuer.audience)).subject("42").issuedAt(now)
            .expiresAt(now.plusSeconds(900)).claim("type", "access");
    }

    private String sign(JwtClaimsSet claims) {
        var header = JwsHeader.with(MacAlgorithm.HS256).build();

        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
