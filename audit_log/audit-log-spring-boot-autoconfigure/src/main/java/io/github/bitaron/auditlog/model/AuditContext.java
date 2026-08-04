package io.github.bitaron.auditlog.model;

/**
 * Immutable snapshot of everything known about one audited method invocation: who did it, from
 * where, with what arguments, and what happened.
 * <p>
 * Produced once per invocation by {@link io.github.bitaron.auditlog.core.AuditContextResolver}
 * and passed unchanged through {@link io.github.bitaron.auditlog.core.AuditLogger},
 * {@link io.github.bitaron.auditlog.core.AuditLogWriter}, and the pluggable
 * {@link io.github.bitaron.auditlog.contract.AuditLogTemplateResolver} /
 * {@link io.github.bitaron.auditlog.contract.AuditLogArgumentSerializer} strategies - none of
 * which may mutate it or perform further context lookups of their own.
 *
 * @param actorId          the identifier of the actor (user or system) initiating the action, or
 *                          {@code null} if unavailable
 * @param actorName        a human-readable name for the actor, or {@code null} if unavailable
 * @param clientLocation   a resolved geographic location for {@code clientIp}, or {@code null}
 * @param clientIp         the caller's IP address, or {@code null} if unavailable
 * @param userAgent        the caller's {@code User-Agent} header, or {@code null} if unavailable
 * @param args              the audited method's arguments, as captured by the aspect
 * @param result           the method's return value on success, or {@code null} on failure
 * @param exception        the thrown exception on failure, or {@code null} on success
 * @param exceptionThrown  {@code true} if the method terminated with an exception
 * @param durationMillis   how long the audited method took to execute (or throw), in
 *                          milliseconds
 * @param traceId          the current distributed-tracing trace id (read from MDC), or
 *                          {@code null} if none is active
 */
public record AuditContext(
        String actorId,
        String actorName,
        String clientLocation,
        String clientIp,
        String userAgent,
        Object args,
        Object result,
        Object exception,
        boolean exceptionThrown,
        long durationMillis,
        String traceId
) {
}
