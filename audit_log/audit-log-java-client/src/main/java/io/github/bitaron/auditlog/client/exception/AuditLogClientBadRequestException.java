package io.github.bitaron.auditlog.client.exception;

/**
 * The server rejected the request itself (any {@code 4xx} other than {@code 401}) - e.g. a
 * {@code size}/{@code limit} above {@code audit.log.query.max-page-size}, a missing required
 * {@code auditType}, or a missing tenant when the server requires one. {@link #getMessage()}
 * includes the server's response body, which {@code AuditServerExceptionHandler} populates with
 * the originating exception's message.
 */
public class AuditLogClientBadRequestException extends AuditLogClientException {

    public AuditLogClientBadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
