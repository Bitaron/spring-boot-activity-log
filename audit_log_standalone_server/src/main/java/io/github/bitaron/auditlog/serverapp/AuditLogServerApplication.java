package io.github.bitaron.auditlog.serverapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The reference standalone deployment of {@code audit-log-spring-boot-server}: a caller with no
 * in-process JVM to depend on the library from runs this instead. Deliberately minimal - this
 * module's only job is being a host application; all behavior comes from the {@code audit-log-*}
 * auto-configuration on the classpath (see {@code pom.xml}).
 * <p>
 * Package is distinct from both the library's own {@code io.github.bitaron.auditlog.server} and
 * its test-only {@code TestServerApplication} in that same package, so there's no ambiguity about
 * which is which. {@code AuditLogEntityScanRegistrar}'s additive entity scan already works from
 * any host package - proven today by both {@code TestServerApplication} and
 * {@code audit_log_usage_example}'s {@code ActivityLogApplication} - so no extra {@code @EntityScan}
 * is needed here.
 */
@SpringBootApplication
public class AuditLogServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditLogServerApplication.class, args);
    }
}
