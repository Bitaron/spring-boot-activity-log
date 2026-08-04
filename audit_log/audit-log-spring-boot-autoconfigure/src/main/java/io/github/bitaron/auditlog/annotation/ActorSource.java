package io.github.bitaron.auditlog.annotation;

/**
 * How the actor recorded on an {@link Audit} entry is determined.
 *
 * @since 2.0
 */
public enum ActorSource {

    /**
     * The default: actor and client information come from the configured
     * {@code AuditLogGenericDataGetter} bean if one is present, otherwise from the current HTTP
     * request (headers/proxy chain - see the trust model documented on {@code AuditLogClientData}).
     */
    CONTEXT,

    /**
     * The actor is recorded as the system itself (actor id/name {@code "SYSTEM"}), independent of
     * any request or security context. Intended for scheduled jobs and other non-request-driven
     * invocations.
     */
    SYSTEM,

    /**
     * The actor id is derived by evaluating {@link Audit#actorExpression()} - a SpEL expression
     * over the audited method's result/arguments/exception. Replaces the previous design where
     * the audited method's return type had to implement a library interface so the aspect could
     * downcast it; that intrusive coupling is gone as of 2.0.
     */
    EXPRESSION
}
