package io.github.bitaron.auditlog.client.exception;

/**
 * The server rejected the request's {@code X-API-Key} ({@code 401 Unauthorized}) - the configured
 * API key is missing, wrong, or not recognized for any tenant. See {@code ApiKeyAuthFilter} on the
 * server module.
 */
public class AuditLogClientAuthenticationException extends AuditLogClientException {

    public AuditLogClientAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
