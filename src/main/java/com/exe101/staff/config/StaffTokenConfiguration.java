package com.exe101.staff.config;

import com.exe101.common.security.AccessTokenValidator;
import com.exe101.staff.security.StaffTokenIssuer;
import java.time.Clock;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
public class StaffTokenConfiguration {
    @Bean
    public JwtDecoder staffJwtDecoder(SecretKey customerSigningKey, Clock customerClock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(customerSigningKey)
            .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new AccessTokenValidator(
            customerClock, StaffTokenIssuer.issuer, StaffTokenIssuer.audience));

        return decoder;
    }
}
