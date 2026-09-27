package com.exe101.customer.config;

import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.SecurityProblemHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
@EnableWebSecurity
public class CustomerSecurityConfiguration {
    @Bean
    public SecurityFilterChain customerSecurity(
        HttpSecurity http, CustomerAuthenticationConverter converter, SecurityProblemHandler problems
    ) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.POST, "/customers/register", "/customers/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/customers/me").hasRole("CUSTOMER")
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(problems)
                .accessDeniedHandler(problems))
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
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
}
