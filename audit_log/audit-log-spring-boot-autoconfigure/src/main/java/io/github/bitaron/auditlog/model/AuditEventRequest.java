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
 * @param tenantId        the tenant this event belongs to, or {@code null} if not applicable/known
 *                        - the caller supplies this explicitly, same as {@link #actorId}, since
 *                        there is no ambient request context for this entry point to resolve it
 *                        from (see {@link io.github.bitaron.auditlog.contract.AuditTenantResolver})
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
        String traceId,
        String tenantId
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

    /**
     * Starts a {@link Builder} for the given required {@link #auditType}. Preferred over the
     * canonical (positional, 17-argument) constructor for anything beyond a trivial call - this
     * record has several adjacent {@code String} fields (e.g. {@link #actorId}/{@link #actorName},
     * {@link #clientIp}/{@link #clientLocation}) that are easy to transpose without the compiler
     * noticing.
     */
    public static Builder builder(String auditType) {
        return new Builder(auditType);
    }

    /**
     * Fluent builder for {@link AuditEventRequest}. All normalization/validation still happens in
     * the record's own compact constructor at {@link #build()} time - this builder only assembles
     * the arguments, it doesn't duplicate that logic.
     */
    public static final class Builder {
        private final String auditType;
        private String actionName;
        private String actionType;
        private String groupName;
        private List<String> templates;
        private String actorId;
        private String actorName;
        private String clientIp;
        private String clientLocation;
        private String userAgent;
        private Object args;
        private Object result;
        private Object exception;
        private boolean exceptionThrown;
        private long durationMillis;
        private String traceId;
        private String tenantId;

        private Builder(String auditType) {
            this.auditType = auditType;
        }

        public Builder actionName(String actionName) {
            this.actionName = actionName;
            return this;
        }

        public Builder actionType(String actionType) {
            this.actionType = actionType;
            return this;
        }

        public Builder groupName(String groupName) {
            this.groupName = groupName;
            return this;
        }

        public Builder templates(List<String> templates) {
            this.templates = templates;
            return this;
        }

        public Builder actorId(String actorId) {
            this.actorId = actorId;
            return this;
        }

        public Builder actorName(String actorName) {
            this.actorName = actorName;
            return this;
        }

        public Builder clientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        public Builder clientLocation(String clientLocation) {
            this.clientLocation = clientLocation;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder args(Object args) {
            this.args = args;
            return this;
        }

        public Builder result(Object result) {
            this.result = result;
            return this;
        }

        public Builder exception(Object exception) {
            this.exception = exception;
            return this;
        }

        public Builder exceptionThrown(boolean exceptionThrown) {
            this.exceptionThrown = exceptionThrown;
            return this;
        }

        public Builder durationMillis(long durationMillis) {
            this.durationMillis = durationMillis;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        /** Convenience for the common success case: sets {@link #result}, and clears any
         * previously-set {@link #exception} so the two can't both be populated by accident. */
        public Builder success(Object result) {
            this.result = result;
            this.exception = null;
            this.exceptionThrown = false;
            return this;
        }

        /** Convenience for the common failure case: sets {@link #exception} and
         * {@link #exceptionThrown}, and clears any previously-set {@link #result}. */
        public Builder failure(Object exception) {
            this.exception = exception;
            this.result = null;
            this.exceptionThrown = true;
            return this;
        }

        public AuditEventRequest build() {
            return new AuditEventRequest(auditType, actionName, actionType, groupName, templates,
                    actorId, actorName, clientIp, clientLocation, userAgent, args, result,
                    exception, exceptionThrown, durationMillis, traceId, tenantId);
        }
    }
}
