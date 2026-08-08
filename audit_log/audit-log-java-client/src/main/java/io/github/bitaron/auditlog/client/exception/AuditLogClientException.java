package io.github.bitaron.auditlog.client.exception;

import io.github.bitaron.auditlog.client.AuditLogHttpClient;

/**
 * Base type for every exception {@link AuditLogHttpClient} throws for a failed call to the
 * {@code audit-log-spring-boot-server} REST module. Before WP17, callers had no way to catch a
 * failure from this client specifically without also catching Spring's generic
 * {@code RestClientException} hierarchy (raised by plenty of code that has nothing to do with
 * this client), nor any way to distinguish an authentication failure from a bad request from a
 * server error short of inspecting a raw HTTP status code themselves.
 *
 * @see AuditLogClientAuthenticationException
 * @see AuditLogClientBadRequestException
 * @see AuditLogClientServerException
 * @see AuditLogClientConnectionException
 */
public class AuditLogClientException extends RuntimeException {

    public AuditLogClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
