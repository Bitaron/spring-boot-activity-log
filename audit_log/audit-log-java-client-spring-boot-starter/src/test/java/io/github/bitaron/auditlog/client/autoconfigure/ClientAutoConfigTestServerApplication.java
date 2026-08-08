package io.github.bitaron.auditlog.client.autoconfigure;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Minimal {@code @SpringBootApplication} embedding the real server module, for
 * {@link AuditLogClientAutoConfigurationTest} to point the auto-configured
 * {@code AuditLogHttpClient} bean at over real HTTP. */
@SpringBootApplication
public class ClientAutoConfigTestServerApplication {
}
