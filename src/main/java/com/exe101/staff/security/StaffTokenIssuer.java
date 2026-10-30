package com.exe101.staff.security;

import com.exe101.staff.model.StaffToken;
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
public class StaffTokenIssuer {
    public static String issuer = "exe101-staff";
    public static String audience = "exe101-staff-api";
    public static long lifetimeSeconds = 900;
    private final JwtEncoder encoder;
    private final Clock clock;

    public StaffToken issue(Long staffId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(List.of(audience))
            .subject(staffId.toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(lifetimeSeconds))
            .claim("type", "access")
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String encoded = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return StaffToken.builder()
            .withAccessToken(encoded)
            .withTokenType("Bearer")
            .withExpiresIn(lifetimeSeconds)
            .build();
    }
}
