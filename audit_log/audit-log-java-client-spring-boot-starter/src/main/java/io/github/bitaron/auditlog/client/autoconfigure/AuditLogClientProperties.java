package io.github.bitaron.auditlog.client.autoconfigure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for the auto-registered {@code AuditLogHttpClient} bean, bound under
 * {@code audit.log.client}. Registered independently of {@code AuditLogProperties} (the core
 * starter's own properties) - this module targets a wholly different consumer (a remote caller of
 * the REST server module, possibly not even running the core starter itself).
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "audit.log.client")
public class AuditLogClientProperties {

    /**
     * Master switch. Off by default - {@code AuditLogHttpClient} isn't a Spring Boot starter by
     * design (see its javadoc), so auto-registering it unconditionally the moment this module is
     * on the classpath would be a surprising, silent behavior change; a consumer opts in
     * explicitly, same as {@code audit.log.server.enabled}.
     */
    private boolean enabled = false;

    /**
     * The server module's base URL, e.g. {@code "https://audit.example.com"}. Required when
     * {@link #enabled} is {@code true} - there is no sensible default.
     */
    private String baseUrl;

    /**
     * The value configured as one of {@code audit.log.server.api-keys.<tenantId>} on the server -
     * determines which tenant this client acts as (see the server module's README).
     */
    private String apiKey;

    @NestedConfigurationProperty
    private final Http http = new Http();

    @Getter
    @Setter
    public static class Http {

        /** Connect timeout for every request this client makes. */
        private Duration connectTimeout = Duration.ofSeconds(5);

        /** Read timeout for every request this client makes. */
        private Duration readTimeout = Duration.ofSeconds(30);
    }
}
