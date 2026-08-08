package io.github.bitaron.auditlog.server;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

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
     * Per-tenant API keys (WP16), keyed by tenant id - e.g.
     * {@code audit.log.server.api-keys.acme-corp=<secret-for-acme>}. The value presented via the
     * {@code X-API-Key} header on every request to this module identifies exactly one tenant
     * (see {@link ApiKeyAuthFilter}); that tenant is what {@link ApiKeyAuditTenantResolver} feeds
     * into every write and read from then on, so a caller can only ever act as the tenant its own
     * key was issued for - not a caller-suppliable value it could set to another tenant.
     * <p>
     * Required (non-empty) when {@link #enabled} is {@code true} - there is no safe default that
     * leaves audit ingestion/read endpoints open, so no configured keys fails startup rather than
     * running unauthenticated (see {@code AuditLogServerAutoConfiguration}). This module also
     * requires {@code audit.log.multi-tenancy.enabled=true} whenever it's enabled: per-tenant keys
     * without the core starter's tenant-scoped read enforcement would authenticate a tenant without
     * ever actually confining that tenant's reads to its own data.
     * <p>
     * Still a first cut, not a complete auth solution: a static key per tenant has no rotation or
     * revocation story of its own. Production deployments should front this module with real
     * authn/authz (mTLS, an OAuth2 resource server, network policy) - see this module's README.
     */
    private Map<String, String> apiKeys = new LinkedHashMap<>();
}
