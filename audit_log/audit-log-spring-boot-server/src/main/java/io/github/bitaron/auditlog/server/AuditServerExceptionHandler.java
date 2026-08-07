package io.github.bitaron.auditlog.server;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

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
 * <p>
 * Writes directly to {@link HttpServletResponse} rather than returning a {@code String} for Spring
 * MVC's usual content-negotiated rendering - {@code AuditLogHttpClient} (WP17's typed exception
 * hierarchy, in {@code audit-log-java-client}) always sends {@code Accept: application/x-protobuf},
 * which no converter can satisfy for a plain-text error body; letting content negotiation own this
 * response turned every 400 this handler was meant to produce for a protobuf-only client into an
 * uncaught {@code HttpMediaTypeNotAcceptableException}, surfacing as an unhelpful {@code 500}
 * instead. Caught empirically writing {@code AuditLogHttpClientErrorHandlingTest}.
 */
@RestControllerAdvice
public class AuditServerExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public void handleCallerError(RuntimeException e, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.getWriter().write(e.getMessage() == null ? "" : e.getMessage());
        response.getWriter().flush();
    }
}
