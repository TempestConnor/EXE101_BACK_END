package com.exe101.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AppErrorMessage {
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email is already registered."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials or account unavailable."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "A valid access token is required."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access is denied."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User was not found."),
    USER_NOT_ACTIVE(HttpStatus.CONFLICT, "User account is not active."),
    ARTIST_ALREADY_GRANTED(HttpStatus.CONFLICT, "User already has an Artist Profile."),
    PROVINCE_INVALID(HttpStatus.BAD_REQUEST, "Province code or name is invalid."),
    PROVINCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Province was not found."),
    PROVINCE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Province code already exists."),
    PROVINCE_UNKNOWN(HttpStatus.UNPROCESSABLE_CONTENT, "Province code is not a known delivery area."),
    PROVINCE_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT, "Delivery to this province is not supported."),
    ADDRESS_INVALID(HttpStatus.BAD_REQUEST, "Address fields are invalid."),
    ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "Address was not found."),
    DEFAULT_ADDRESS_CONFLICT(HttpStatus.CONFLICT, "Default address was changed concurrently; retry."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");

    private final HttpStatus status;
    private final String detail;
}
