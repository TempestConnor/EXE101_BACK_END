package com.exe101.customer.config;

import com.exe101.customer.security.CustomerTokenValidator;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
public class CustomerTokenConfiguration {
    @Bean
    public Clock customerClock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("pbkdf2", Map.of(
            "pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }

    @Bean
    public SecretKey customerSigningKey(@Value("${app.jwt.secret}") String encodedSecret) {
        byte[] secret = Base64.getDecoder().decode(encodedSecret);

        if (secret.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 random bytes encoded as Base64.");
        }

        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    public JwtEncoder customerJwtEncoder(SecretKey customerSigningKey) {
        return NimbusJwtEncoder.withSecretKey(customerSigningKey).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    public JwtDecoder customerJwtDecoder(SecretKey customerSigningKey, Clock customerClock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(customerSigningKey)
            .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new CustomerTokenValidator(customerClock));

        return decoder;
    }
}
