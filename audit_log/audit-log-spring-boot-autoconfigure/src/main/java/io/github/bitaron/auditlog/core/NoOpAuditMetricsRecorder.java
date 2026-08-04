package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.contract.AuditMetricsRecorder;

/** Default {@link AuditMetricsRecorder}: does nothing. Used when Micrometer isn't on the classpath. */
public class NoOpAuditMetricsRecorder implements AuditMetricsRecorder {

    @Override
    public void recordWritten() {
    }

    @Override
    public void recordFailed() {
    }

    @Override
    public void recordRejected() {
    }

    @Override
    public void recordDroppedOnShutdown(int count) {
    }
}
