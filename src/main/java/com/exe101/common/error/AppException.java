package com.exe101.common.error;

import lombok.Getter;

@Getter
public class AppException extends RuntimeException {
    private final AppErrorMessage error;
    // Optional response body part, such as the fresh checkout preview of a rejected Order; already a DTO.
    private final transient Object details;

    public AppException(AppErrorMessage error) {
        this(error, null);
    }

    public AppException(AppErrorMessage error, Object details) {
        super(error.getDetail());
        this.error = error;
        this.details = details;
    }
}
