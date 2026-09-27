package com.exe101.customer.security;

import com.exe101.common.error.AppErrorMessage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    @Override
    public void commence(
        HttpServletRequest request, HttpServletResponse response, AuthenticationException exception
    ) throws IOException {
        if (exception instanceof AuthenticationServiceException) {
            log.error("[CUSTOMER] - ACTION: authentication: infrastructure failure");
            write(response, AppErrorMessage.INTERNAL_ERROR);

            return;
        }

        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, AppErrorMessage.AUTHENTICATION_REQUIRED);
    }

    @Override
    public void handle(
        HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception
    ) throws IOException {
        write(response, AppErrorMessage.ACCESS_DENIED);
    }

    private void write(HttpServletResponse response, AppErrorMessage error) throws IOException {
        response.setStatus(error.getStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
            {"type":"about:blank","title":"%s","status":%d,"detail":"%s","errorCode":"%s"}
            """.formatted(error.getStatus().getReasonPhrase(), error.getStatus().value(),
                error.getDetail(), error.name()));
    }
}
