package io.github.bitaron.auditlog.contract;

/**
 * Records the outcome of every audit-write attempt, including the ones that never reach the
 * database. Every place the audit pipeline can silently drop a record - a full executor queue, a
 * write failure, an application shutdown with writes still queued - calls exactly one of these
 * methods, so "how many audit records did we lose, and why" is an observable question instead of
 * a {@code WARN} line someone has to be watching for in real time.
 * <p>
 * The default implementation is a no-op; a Micrometer-backed implementation is registered
 * automatically when Micrometer is on the classpath. Implement this yourself to wire audit
 * delivery into a different metrics system.
 *
 * @since 2.0
 */
public interface AuditMetricsRecorder {

    /** An audit record was successfully persisted. */
    void recordWritten();

    /** An audit write was attempted and failed (a bad template, a database error, ...). */
    void recordFailed();

    /** An audit write was rejected outright because the delivery executor's queue was full. */
    void recordRejected();

    /**
     * Audit writes were still queued or in flight when the application shut down and did not
     * complete within the configured grace period.
     *
     * @param count number of writes that did not complete
     */
    void recordDroppedOnShutdown(int count);
}
