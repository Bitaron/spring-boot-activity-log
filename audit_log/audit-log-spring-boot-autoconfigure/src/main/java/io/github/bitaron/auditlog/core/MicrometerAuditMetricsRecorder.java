package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.contract.AuditMetricsRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@link AuditMetricsRecorder} backed by a {@code audit.log.records} counter, tagged
 * {@code outcome=written|failed|rejected|dropped_on_shutdown}.
 * <p>
 * Only ever constructed from {@code AuditLogMicrometerConfiguration}, which is guarded by
 * {@code @ConditionalOnClass(MeterRegistry.class)} - this class is never loaded at all in an
 * application without Micrometer on the classpath, so referencing Micrometer types here directly
 * is safe.
 */
public class MicrometerAuditMetricsRecorder implements AuditMetricsRecorder {

    private static final String METRIC_NAME = "audit.log.records";

    private final Counter written;
    private final Counter failed;
    private final Counter rejected;
    private final Counter droppedOnShutdown;

    public MicrometerAuditMetricsRecorder(MeterRegistry registry) {
        this.written = counter(registry, "written");
        this.failed = counter(registry, "failed");
        this.rejected = counter(registry, "rejected");
        this.droppedOnShutdown = counter(registry, "dropped_on_shutdown");
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder(METRIC_NAME)
                .description("Outcomes of audit-log delivery attempts")
                .tag("outcome", outcome)
                .register(registry);
    }

    @Override
    public void recordWritten() {
        written.increment();
    }

    @Override
    public void recordFailed() {
        failed.increment();
    }

    @Override
    public void recordRejected() {
        rejected.increment();
    }

    @Override
    public void recordDroppedOnShutdown(int count) {
        droppedOnShutdown.increment(count);
    }
}
