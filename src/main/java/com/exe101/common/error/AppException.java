package com.exe101.common.error;

import lombok.Getter;

@Getter
public class AppException extends RuntimeException {
    private final AppErrorMessage error;

    public AppException(AppErrorMessage error) {
        super(error.getDetail());
        this.error = error;
    }
}
