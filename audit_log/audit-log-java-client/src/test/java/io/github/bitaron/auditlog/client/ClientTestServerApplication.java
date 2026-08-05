package io.github.bitaron.auditlog.client;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Minimal {@code @SpringBootApplication} embedding the real server module for
 * {@link AuditLogHttpClientTest} to round-trip against. */
@SpringBootApplication
public class ClientTestServerApplication {
}
