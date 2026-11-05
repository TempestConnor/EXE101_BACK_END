package com.exe101.common.error;

import java.util.Map;
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
    private static String invalidRequest = "INVALID_REQUEST";
    private static Map<Integer, String> frameworkCodes = Map.of(
        404, "NOT_FOUND",
        405, "METHOD_NOT_ALLOWED",
        406, "NOT_ACCEPTABLE",
        413, "PAYLOAD_TOO_LARGE",
        415, "UNSUPPORTED_MEDIA_TYPE");

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Object> handleApplication(AppException exception) {
        AppErrorMessage error = exception.getError();
        log.warn("[API] - ACTION: reject: code: {}", error);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(error.getStatus(), error.getDetail());
        problem.setProperty("errorCode", error.name());

        if (exception.getDetails() != null) {
            problem.setProperty("details", exception.getDetails());
        }

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

    // Spring's own errors (malformed body, wrong method or media type, an upload over the multipart limit) get a code
    // from their status, so a client can tell them apart; a plain 400 stays INVALID_REQUEST.
    @Override
    protected ResponseEntity<Object> createResponseEntity(
        Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        if (body instanceof ProblemDetail problem) {
            problem.setProperty("errorCode", frameworkCode(status));
        }

        return super.createResponseEntity(body, headers, status, request);
    }

    private String frameworkCode(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return AppErrorMessage.INTERNAL_ERROR.name();
        }

        return frameworkCodes.getOrDefault(status.value(), invalidRequest);
    }
}
