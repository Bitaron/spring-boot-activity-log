package io.github.bitaron.auditlog.entity;

/** The final outcome of the audited method invocation that produced an {@link AuditLog} row. */
public enum AuditOutcome {
    SUCCESS,
    FAILURE
}
