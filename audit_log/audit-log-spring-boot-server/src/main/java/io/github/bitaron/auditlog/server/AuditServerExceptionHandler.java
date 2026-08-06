package io.github.bitaron.auditlog.server;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates caller-error exceptions into {@code 400 Bad Request} - without this, Spring MVC's
 * default is {@code 500}, which would misrepresent a caller mistake as a server fault.
 * <ul>
 *   <li>{@link IllegalArgumentException} - {@code AuditLogQueryService} and
 *   {@code AuditEventRequest} already throw this for an oversized page size, an unsupported sort
 *   property, or a missing {@code auditType}; {@code AuditIngestController} throws it for a
 *   missing {@code tenant_id} when {@code audit.log.server.multi-tenancy.required=true}.</li>
 *   <li>{@link IllegalStateException} - {@code JpaAuditLogQueryService} throws this when
 *   {@code audit.log.multi-tenancy.enabled=true} but the request carries no resolvable tenant
 *   (e.g. the {@code X-TENANT-ID} header is missing) - the caller's request is what's incomplete,
 *   even though the check itself lives on the query service rather than the controller.</li>
 * </ul>
 */
@RestControllerAdvice
public class AuditServerExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleCallerError(RuntimeException e) {
        return e.getMessage();
    }
}
