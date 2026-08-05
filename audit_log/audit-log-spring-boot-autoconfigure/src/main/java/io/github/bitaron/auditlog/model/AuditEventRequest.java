package io.github.bitaron.auditlog.model;

import io.github.bitaron.auditlog.contract.AuditLogRecorder;

import java.util.List;

/**
 * A fully-described audit event, for {@link AuditLogRecorder#record} - the entry point for
 * callers that have no {@code @Audit}-annotated method invocation to intercept (an HTTP ingestion
 * endpoint, a message-queue consumer, a batch job) and so must supply everything themselves
 * instead of it being captured by AOP or resolved from an ambient HTTP request.
 * <p>
 * Mirrors the fields a {@code @Audit} annotation plus {@link AuditContext} would otherwise
 * together provide. Notably absent: {@code actorSource}/{@code actorExpression} - both only make
 * sense relative to a join point (a return value/arguments to evaluate a SpEL expression against),
 * which doesn't exist here; the caller supplies the already-resolved {@link #actorId}/
 * {@link #actorName} directly instead.
 *
 * @param auditType       a high-level category for the audited action; required
 * @param actionName      a short, human-readable name for the specific action, or {@code ""}
 * @param actionType      the kind of operation (e.g. "CREATE"), or {@code ""}
 * @param groupName       shared audit group name, or {@code ""} to skip grouping
 * @param templates       names of {@code audit_template} rows to render this event's message(s);
 *                        empty for a single audit row with no rendered message
 * @param actorId         the identifier of the actor initiating the action, or {@code null}
 * @param actorName       a human-readable name for the actor, or {@code null}
 * @param clientIp        the caller's IP address, or {@code null} if not applicable/known
 * @param clientLocation  a resolved geographic location for {@code clientIp}, or {@code null}
 * @param userAgent       the caller's user agent, or {@code null} if not applicable/known
 * @param args            data describing what was done, serialized the same way method arguments
 *                        are for an AOP-captured event, or {@code null}
 * @param result          the outcome payload on success, or {@code null}
 * @param exception       the failure payload on failure, or {@code null}
 * @param exceptionThrown {@code true} if this event describes a failure
 * @param durationMillis  how long the described operation took, in milliseconds, or {@code 0}
 * @param traceId         a distributed-tracing trace id to correlate this event with, or
 *                        {@code null}
 */
public record AuditEventRequest(
        String auditType,
        String actionName,
        String actionType,
        String groupName,
        List<String> templates,
        String actorId,
        String actorName,
        String clientIp,
        String clientLocation,
        String userAgent,
        Object args,
        Object result,
        Object exception,
        boolean exceptionThrown,
        long durationMillis,
        String traceId
) {
    public AuditEventRequest {
        if (auditType == null || auditType.isBlank()) {
            throw new IllegalArgumentException("AuditEventRequest.auditType is required");
        }
        actionName = actionName == null ? "" : actionName;
        actionType = actionType == null ? "" : actionType;
        groupName = groupName == null ? "" : groupName;
        templates = templates == null ? List.of() : List.copyOf(templates);
    }
}
