package io.github.bitaron.auditlog.query;

import java.time.LocalDateTime;

/**
 * Filter for {@link AuditLogQueryService#find}. Every field is optional (null = unfiltered);
 * fields correspond to {@code audit_log}'s indexed columns
 * ({@code actor_id}, {@code audit_type}, {@code created_at}), which is why those three and only
 * those three are filterable here.
 *
 * @param actorId       exact match on the actor id, or {@code null} for any actor
 * @param auditType     exact match on the audit type, or {@code null} for any type
 * @param createdAtFrom inclusive lower bound on {@code created_at}, or {@code null} for no lower bound
 * @param createdAtTo   inclusive upper bound on {@code created_at}, or {@code null} for no upper bound
 */
public record AuditQuery(String actorId, String auditType, LocalDateTime createdAtFrom, LocalDateTime createdAtTo) {

    public static AuditQuery all() {
        return new AuditQuery(null, null, null, null);
    }

    /** Filters to a single actor, with every other field unfiltered. */
    public static AuditQuery byActor(String actorId) {
        return new AuditQuery(actorId, null, null, null);
    }

    /** Filters to a single audit type, with every other field unfiltered. */
    public static AuditQuery byType(String auditType) {
        return new AuditQuery(null, auditType, null, null);
    }

    /** Filters to a single actor and audit type, with the created-at range unfiltered. */
    public static AuditQuery byActorAndType(String actorId, String auditType) {
        return new AuditQuery(actorId, auditType, null, null);
    }

    /** Returns a copy of this query with {@code createdAtFrom}/{@code createdAtTo} replaced. */
    public AuditQuery withCreatedBetween(LocalDateTime createdAtFrom, LocalDateTime createdAtTo) {
        return new AuditQuery(actorId, auditType, createdAtFrom, createdAtTo);
    }

    /** Returns a copy of this query with {@code actorId} replaced. */
    public AuditQuery withActor(String actorId) {
        return new AuditQuery(actorId, auditType, createdAtFrom, createdAtTo);
    }

    /** Returns a copy of this query with {@code auditType} replaced. */
    public AuditQuery withType(String auditType) {
        return new AuditQuery(actorId, auditType, createdAtFrom, createdAtTo);
    }
}
