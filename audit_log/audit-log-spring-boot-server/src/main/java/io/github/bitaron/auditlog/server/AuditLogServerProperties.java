package io.github.bitaron.auditlog.server;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the optional REST ingestion/query server, bound under {@code audit.log.server}.
 * <p>
 * Registered independently of {@code AuditLogProperties} (the core starter's properties) since
 * this is a separate, opt-in module a consumer may not even have on the classpath.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "audit.log.server")
public class AuditLogServerProperties {

    /**
     * Master switch. Off by default - depending on this module must not silently expose HTTP
     * endpoints; a consumer opts in explicitly.
     */
    private boolean enabled = false;

    /**
     * Shared-secret value required (via the {@code X-API-Key} header) on every request to this
     * module's endpoints. Required when {@link #enabled} is {@code true} - there is no safe
     * default that leaves audit ingestion/read endpoints open, so an unset key fails startup
     * rather than running unauthenticated (see {@code AuditLogServerAutoConfiguration}).
     * <p>
     * This is a first cut, not a complete auth solution: a single static shared secret has no
     * per-caller identity, rotation story, or revocation. Production deployments should front this
     * module with real authn/authz (mTLS, an OAuth2 resource server, network policy) - see this
     * module's README.
     */
    private String apiKey;
}
