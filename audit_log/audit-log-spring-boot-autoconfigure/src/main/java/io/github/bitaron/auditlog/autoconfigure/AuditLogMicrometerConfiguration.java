package io.github.bitaron.auditlog.autoconfigure;

import io.github.bitaron.auditlog.contract.AuditMetricsRecorder;
import io.github.bitaron.auditlog.core.MicrometerAuditMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers a Micrometer-backed {@link AuditMetricsRecorder} when Micrometer is present, so
 * silent drops in the delivery pipeline (a full queue, a write failure, records still queued at
 * shutdown) become an observable {@code audit.log.records} counter instead of only a log line.
 * <p>
 * Kept in a separate {@code @Configuration} (rather than a plain {@code @Bean} method on the
 * auto-configuration class) so that {@code @ConditionalOnClass} can prevent this class from ever
 * being loaded - and Micrometer types resolved - in an application that doesn't have Micrometer
 * on its classpath at all. See {@link MicrometerAuditMetricsRecorder}.
 * <p>
 * {@code @ConditionalOnBean(MeterRegistry.class)} additionally requires an actual registry bean,
 * not just the Micrometer jar, since a {@code MeterRegistry} constructor parameter with none
 * available would fail application startup rather than degrading to the no-op recorder. The
 * name-based {@code @AutoConfigureAfter} orders this behind Boot's actuator metrics
 * auto-configuration (by class name, so it isn't a compile-time dependency of this module) so the
 * registry bean it creates is visible to the {@code @ConditionalOnBean} check above.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(MeterRegistry.class)
@AutoConfigureAfter(name = "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration")
class AuditLogMicrometerConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditMetricsRecorder.class)
    @ConditionalOnBean(MeterRegistry.class)
    AuditMetricsRecorder micrometerAuditMetricsRecorder(MeterRegistry meterRegistry) {
        return new MicrometerAuditMetricsRecorder(meterRegistry);
    }
}
