package io.github.bitaron.auditlog.client.exception;

/**
 * The request never reached the server (or its response never arrived) - a connection refused,
 * a DNS failure, or a timeout. Wraps Spring's {@code ResourceAccessException}.
 */
public class AuditLogClientConnectionException extends AuditLogClientException {

    public AuditLogClientConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
