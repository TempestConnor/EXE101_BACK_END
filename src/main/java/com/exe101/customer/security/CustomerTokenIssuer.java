package com.exe101.customer.security;

import com.exe101.customer.model.CustomerToken;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CustomerTokenIssuer {
    public static String issuer = "exe101-customer";
    public static String audience = "exe101-customer-api";
    public static long lifetimeSeconds = 900;
    private final JwtEncoder encoder;
    private final Clock clock;

    public CustomerToken issue(Long userId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(List.of(audience))
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(lifetimeSeconds))
            .claim("type", "access")
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String encoded = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return CustomerToken.builder()
            .withAccessToken(encoded)
            .withTokenType("Bearer")
            .withExpiresIn(lifetimeSeconds)
            .build();
    }
}
