package io.github.bitaron.auditlog.contract;

import io.github.bitaron.auditlog.model.AuditEventRequest;

/**
 * Programmatic entry point for recording an audit event without an {@code @Audit}-annotated
 * method invocation for the AOP aspect to intercept.
 * <p>
 * Before this interface, the <em>only</em> way to write an audit record was {@code @Audit} + AOP -
 * fine for in-process method calls, but unworkable for an HTTP ingestion endpoint (see the
 * {@code audit-log-spring-boot-server} module), a message-queue consumer, or any other caller that
 * has data describing an event but no method invocation for AOP to intercept.
 * <p>
 * Delivery (sync/async, commit-aware dispatch, metrics) is identical to the {@code @Audit} path -
 * this interface only replaces how the event's data gets in, not how it's written afterward.
 *
 * @see AuditEventRequest
 */
public interface AuditLogRecorder {

    /**
     * Records one audit event, following the same delivery pipeline (mode, transaction-commit
     * awareness, metrics, failure isolation) as an {@code @Audit}-annotated method invocation.
     *
     * @param request the event to record
     */
    void record(AuditEventRequest request);
}
