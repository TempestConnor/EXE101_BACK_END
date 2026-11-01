package com.exe101.common.security;

import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
@EnableWebSecurity
public class SecurityConfiguration {
    @Bean
    public SecurityFilterChain apiSecurity(
        HttpSecurity http, SecurityProblemHandler problems,
        @Qualifier("customerJwtDecoder") JwtDecoder customerDecoder, CustomerAuthenticationConverter customerConverter,
        @Qualifier("staffJwtDecoder") JwtDecoder staffDecoder, StaffAuthenticationConverter staffConverter
    ) throws Exception {
        // The unverified issuer only selects a verifier; each verifier checks signature, issuer and audience.
        Map<String, AuthenticationManager> managers = Map.of(
            CustomerTokenIssuer.issuer, manager(customerDecoder, customerConverter),
            StaffTokenIssuer.issuer, manager(staffDecoder, staffConverter));
        JwtIssuerAuthenticationManagerResolver resolver = new JwtIssuerAuthenticationManagerResolver(managers::get);

        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.POST, "/customers/register", "/customers/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/staff/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/customers/me").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.POST, "/artists").hasRole("ADMIN")
                .requestMatchers("/addresses", "/addresses/*").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.GET, "/provinces/supported").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.GET, "/provinces").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/provinces").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/provinces/*/support").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/tags/active").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.GET, "/tags").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/tags").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/tags/*").hasRole("ADMIN")
                .requestMatchers("/artworks", "/artworks/**").hasRole("CUSTOMER")
                .requestMatchers("/admin/artworks", "/admin/artworks/**").hasRole("ADMIN")
                .requestMatchers("/listings", "/listings/**").hasRole("CUSTOMER")
                .requestMatchers("/admin/listings", "/admin/listings/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/purchases/assets/*/content").hasRole("CUSTOMER")
                .requestMatchers("/cart", "/cart/**").hasRole("CUSTOMER")
                .requestMatchers("/orders", "/orders/**").hasRole("CUSTOMER")
                // Authenticated by the VNPay signature, not by a token.
                .requestMatchers(HttpMethod.GET, "/payments/vnpay/ipn").permitAll()
                .requestMatchers(HttpMethod.GET, "/catalog/artworks", "/catalog/artworks/**").permitAll()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(problems)
                .accessDeniedHandler(problems))
            .oauth2ResourceServer(resource -> resource
                .authenticationManagerResolver(resolver)
                .authenticationEntryPoint(problems)
                .accessDeniedHandler(problems)
                .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                    @Override
                    public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                        filter.setAuthenticationFailureHandler(problems::commence);

                        return filter;
                    }
                }));

        return http.build();
    }

    private AuthenticationManager manager(
        JwtDecoder decoder, Converter<Jwt, AbstractAuthenticationToken> converter
    ) {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);

        return new ProviderManager(provider);
    }
}
