package com.exe101.customer.security;

import com.exe101.common.error.AppException;
import com.exe101.customer.service.CustomerService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CustomerAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final CustomerService customerService;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());

        try {
            customerService.requireActive(userId);
        } catch (AppException exception) {
            throw new BadCredentialsException("Customer account unavailable.");
        } catch (RuntimeException exception) {
            throw new AuthenticationServiceException("Customer authentication unavailable.", exception);
        }

        return UsernamePasswordAuthenticationToken.authenticated(
            userId, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }
}
