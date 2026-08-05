package io.github.bitaron.auditlog.server;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates the {@link IllegalArgumentException}s {@code AuditLogQueryService} and
 * {@code AuditEventRequest} already throw for caller error (an oversized page size, an
 * unsupported sort property, a missing {@code auditType}) into {@code 400 Bad Request} - without
 * this, Spring MVC's default is {@code 500}, which would misrepresent a caller mistake as a
 * server fault.
 */
@RestControllerAdvice
public class AuditServerExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleIllegalArgument(IllegalArgumentException e) {
        return e.getMessage();
    }
}
