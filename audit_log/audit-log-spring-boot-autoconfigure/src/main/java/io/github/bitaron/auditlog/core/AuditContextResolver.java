package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.model.AuditContext;

/**
 * Builds the {@link AuditContext} for one audited method invocation.
 * <p>
 * Implementations own all actor/client resolution - reading {@code RequestContextHolder}, HTTP
 * headers, a configured {@code AuditLogGenericDataGetter}, and so on. Nothing outside an
 * implementation of this interface should read that kind of static/ambient state, which is what
 * keeps {@link AuditContext} itself a plain, constructor-lookup-free value type.
 *
 * @see DefaultAuditContextResolver
 */
public interface AuditContextResolver {

    /**
     * Resolves the {@link AuditContext} for one invocation.
     *
     * @param audit                 the {@code @Audit} annotation from the intercepted method
     * @param args                  the method's arguments, as captured by the aspect
     * @param result                the method's return value on success, or the thrown exception
     *                              on failure
     * @param exceptionThrown       {@code true} if the method terminated with an exception
     * @param expressionActorValue the value {@code actorExpression} evaluated to, already
     *                              resolved by the caller; only consulted when
     *                              {@code audit.actorSource() == ActorSource.EXPRESSION}
     * @param durationMillis       how long the audited method took to execute (or throw), as
     *                              measured by the caller
     * @return the resolved, immutable audit context
     */
    AuditContext resolve(Audit audit, Object args, Object result, boolean exceptionThrown,
                          String expressionActorValue, long durationMillis);
}
