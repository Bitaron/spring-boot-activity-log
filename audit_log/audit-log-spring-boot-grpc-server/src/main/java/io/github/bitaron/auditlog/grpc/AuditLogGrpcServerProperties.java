package io.github.bitaron.auditlog.grpc;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for the optional gRPC ingestion/query server, bound under {@code audit.log.grpc}.
 * <p>
 * A separate key namespace from {@code audit.log.server.*} (the REST module's own properties)
 * rather than reusing it, even though both are "per-tenant API keys for this application's audit
 * ingestion boundary" conceptually - the two modules are independently optional and independently
 * deployable (a consumer might run only one of them), so neither should require the other's
 * properties class on the classpath just to bind its own config. Running both in the <em>same</em>
 * application isn't supported at all - see {@link AuditLogGrpcServerAutoConfiguration}'s javadoc.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "audit.log.grpc")
public class AuditLogGrpcServerProperties {

    /**
     * Master switch. Off by default - depending on this module must not silently expose a gRPC
     * port; a consumer opts in explicitly.
     */
    private boolean enabled = false;

    /** Port {@link AuditLogGrpcServer} binds to. */
    private int port = 9090;

    /**
     * Per-tenant API keys, keyed by tenant id - e.g.
     * {@code audit.log.grpc.api-keys.acme-corp=<secret-for-acme>}. The value presented via the
     * {@code x-api-key} gRPC metadata entry on every call identifies exactly one tenant (see
     * {@link ApiKeyGrpcServerInterceptor}); that tenant is what {@link GrpcAuditTenantResolver}
     * feeds into every write and read from then on, so a caller can only ever act as the tenant its
     * own key was issued for.
     * <p>
     * Required (non-empty) when {@link #enabled} is {@code true} - there is no safe default that
     * leaves this port unauthenticated. This module also requires
     * {@code audit.log.multi-tenancy.enabled=true} whenever it's enabled, for the identical reason
     * the REST server module does - see {@code AuditLogServerProperties.apiKeys}'s javadoc.
     */
    private Map<String, String> apiKeys = new LinkedHashMap<>();
}
