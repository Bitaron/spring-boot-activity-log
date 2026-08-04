package io.github.bitaron.auditlog.contract;

/**
 * Strategy for turning an audited method's arguments/response into the JSON persisted in the
 * {@code audit_log.data} column.
 * <p>
 * Implementations are responsible for skipping types that cannot or should not be serialized
 * (servlet request/response objects, security principals, uploaded files, ...) and for masking
 * sensitive values, since every argument passed to an {@code @Audit}-annotated method is
 * captured by default.
 *
 * @since 1.1
 */
public interface AuditLogArgumentSerializer {

    /**
     * Serializes the given value to a JSON string.
     *
     * @param value the value to serialize; may be {@code null}
     * @return a JSON representation of {@code value}, or {@code null} if {@code value} is
     *         {@code null}
     */
    String serialize(Object value);
}
