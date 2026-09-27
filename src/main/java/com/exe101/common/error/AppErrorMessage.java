package com.exe101.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AppErrorMessage {
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email is already registered."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials or account unavailable."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "A valid customer access token is required."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access is denied."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");

    private final HttpStatus status;
    private final String detail;
}
