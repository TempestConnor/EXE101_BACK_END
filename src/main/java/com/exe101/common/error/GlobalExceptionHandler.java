package com.exe101.common.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(AppException.class)
    public ResponseEntity<Object> handleApplication(AppException exception) {
        AppErrorMessage error = exception.getError();
        log.warn("[CUSTOMER] - ACTION: reject: code: {}", error);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(error.getStatus(), error.getDetail());
        problem.setProperty("errorCode", error.name());
        HttpHeaders headers = new HttpHeaders();

        if (error.getStatus().value() == 401) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }

        return new ResponseEntity<>(problem, headers, error.getStatus());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception) {
        log.error("[API] - ACTION: failure: type: {}", exception.getClass().getSimpleName());
        AppErrorMessage error = AppErrorMessage.INTERNAL_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(error.getStatus(), error.getDetail());
        problem.setProperty("errorCode", error.name());

        return ResponseEntity.status(error.getStatus()).body(problem);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
        Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        if (body instanceof ProblemDetail problem) {
            problem.setProperty("errorCode", "INVALID_REQUEST");
        }

        return super.createResponseEntity(body, headers, status, request);
    }
}
