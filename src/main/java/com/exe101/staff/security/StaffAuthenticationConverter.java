package com.exe101.staff.security;

import com.exe101.common.error.AppException;
import com.exe101.staff.model.Staff;
import com.exe101.staff.service.StaffService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StaffAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final StaffService staffService;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long staffId = Long.valueOf(jwt.getSubject());
        Staff staff;

        // Role comes from the current account row, never from the token.
        try {
            staff = staffService.requireActive(staffId);
        } catch (AppException exception) {
            throw new BadCredentialsException("Staff account unavailable.");
        } catch (RuntimeException exception) {
            throw new AuthenticationServiceException("Staff authentication unavailable.", exception);
        }

        return UsernamePasswordAuthenticationToken.authenticated(staffId, null, List.of(
            new SimpleGrantedAuthority("ROLE_STAFF"), new SimpleGrantedAuthority("ROLE_" + staff.getRole())));
    }
}
