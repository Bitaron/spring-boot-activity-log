package io.github.bitaron.auditlog.client.exception;

/**
 * The server responded with a {@code 5xx} status - the request was well-formed, but the server
 * failed to process it.
 */
public class AuditLogClientServerException extends AuditLogClientException {

    public AuditLogClientServerException(String message, Throwable cause) {
        super(message, cause);
    }
}
