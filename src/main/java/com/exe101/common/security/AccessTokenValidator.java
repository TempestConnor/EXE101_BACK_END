package com.exe101.common.security;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

@RequiredArgsConstructor
public class AccessTokenValidator implements OAuth2TokenValidator<Jwt> {
    private final Clock clock;
    private final String issuer;
    private final String audience;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Instant now = clock.instant();
        boolean valid = issuer.equals(jwt.getClaimAsString("iss"))
            && jwt.getAudience() != null && jwt.getAudience().contains(audience)
            && "access".equals(jwt.getClaimAsString("type"))
            && jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(now)
            && jwt.getIssuedAt() != null && !jwt.getIssuedAt().isAfter(now)
            && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
            && (jwt.getNotBefore() == null || !jwt.getNotBefore().isAfter(now))
            && validSubject(jwt.getSubject());

        if (!valid) {
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Invalid access token.", null));
        }

        return OAuth2TokenValidatorResult.success();
    }

    private boolean validSubject(String subject) {
        if (subject == null) {
            return false;
        }

        try {
            return Long.parseLong(subject) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
