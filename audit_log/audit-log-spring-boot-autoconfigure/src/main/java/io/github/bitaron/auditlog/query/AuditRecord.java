package io.github.bitaron.auditlog.query;

import io.github.bitaron.auditlog.entity.AuditLog;
import io.github.bitaron.auditlog.entity.AuditOutcome;

import java.time.LocalDateTime;

/**
 * Immutable, read-only projection of one {@link AuditLog} row - the supported way for a
 * consuming application to read audit records, so the persistence entity itself doesn't have to
 * double as public API (and stay frozen in shape because of it).
 *
 * @see AuditLogQueryService
 */
public record AuditRecord(
        Long id,
        String auditType,
        String actorId,
        String actorName,
        String clientIp,
        String clientLocation,
        String userAgent,
        String actionType,
        String actionName,
        LocalDateTime createdAt,
        AuditOutcome outcome,
        Long durationMs,
        String traceId,
        String data,
        Long groupId,
        String tenantId
) {
    /**
     * The {@link AuditCursor} marking "everything strictly after this row" for
     * {@link AuditLogQueryService#findAfter} - equivalent to
     * {@code new AuditCursor(createdAt(), id())}, but named for the common case of paging through
     * results one page at a time: {@code AuditCursor next = results.get(results.size() - 1).toCursor();}
     */
    public AuditCursor toCursor() {
        return new AuditCursor(createdAt, id);
    }
}
