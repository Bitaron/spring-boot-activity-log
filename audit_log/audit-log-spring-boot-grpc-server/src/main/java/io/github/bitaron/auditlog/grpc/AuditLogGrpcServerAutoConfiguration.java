package io.github.bitaron.auditlog.grpc;

import io.github.bitaron.auditlog.autoconfigure.AuditLogAutoConfiguration;
import io.github.bitaron.auditlog.contract.AuditLogRecorder;
import io.github.bitaron.auditlog.contract.AuditTenantResolver;
import io.github.bitaron.auditlog.properties.AuditLogProperties;
import io.github.bitaron.auditlog.query.AuditLogQueryService;
import io.grpc.Server;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.HashMap;
import java.util.Map;

/**
 * Auto-configuration for the optional gRPC ingestion/query server (WP18).
 * <p>
 * Gated by {@code audit.log.grpc.enabled} with no {@code matchIfMissing} - unlike the core
 * starter's {@code AuditLogAutoConfiguration} (on by default), depending on this module must not
 * silently open a network port; a consumer opts in explicitly.
 * <p>
 * {@code @AutoConfigureBefore(AuditLogAutoConfiguration.class)}: this module's
 * {@link GrpcAuditTenantResolver} bean must be registered before the core starter's own
 * {@code @ConditionalOnMissingBean} default gets a chance to run, so it - not the spoofable
 * header-based default - is what every {@code DefaultAuditContextResolver}/
 * {@code JpaAuditLogQueryService} in the application resolves against once this module is enabled.
 * Identical reasoning, and an identical mechanism, to {@code AuditLogServerAutoConfiguration}
 * (the REST server module) - see that class's javadoc.
 * <p>
 * <b>Cannot coexist with the REST server module in the same application</b> - see
 * {@link #apiKeyGrpcServerInterceptor}'s fail-fast check. Each module authenticates its own
 * per-tenant keys into a different request-scoped context (an {@code HttpServletRequest} attribute
 * for REST, a gRPC {@link io.grpc.Context} value here), and only one
 * {@code AuditTenantResolver} bean can be active application-wide - whichever module's
 * autoconfiguration processes first would silently win the {@code @ConditionalOnMissingBean} race,
 * and the other protocol's calls would then resolve no tenant at all and fail closed. Rather than
 * leave that as a subtle, order-dependent latent bug, both modules fail startup loudly if the
 * other's {@code enabled} property is also {@code true} - run REST and gRPC as separate deployed
 * instances of the same application instead.
 */
@AutoConfiguration
@AutoConfigureBefore(AuditLogAutoConfiguration.class)
@ConditionalOnClass(Server.class)
@ConditionalOnProperty(prefix = "audit.log.grpc", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AuditLogGrpcServerProperties.class)
public class AuditLogGrpcServerAutoConfiguration {

    /**
     * Always the per-tenant-API-key-backed resolver, never conditioned on
     * {@code audit.log.multi-tenancy.enabled} the way the core starter's default is - see
     * {@link #apiKeyGrpcServerInterceptor} for why this module requires that flag whenever it's
     * enabled anyway.
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditTenantResolver auditTenantResolver() {
        return new GrpcAuditTenantResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogGrpcService auditLogGrpcService(AuditLogRecorder auditLogRecorder,
                                                     AuditLogQueryService auditLogQueryService,
                                                     AuditTenantResolver auditTenantResolver) {
        return new AuditLogGrpcService(auditLogRecorder, auditLogQueryService, auditTenantResolver);
    }

    /**
     * Fails startup rather than starting an unauthenticated/unscoped/conflicting server when this
     * module is enabled with: no per-tenant keys configured; the core starter's tenant-scoped read
     * enforcement turned off (per-tenant keys would then authenticate a tenant identity that
     * nothing confines reads by - identical reasoning to the REST server module's own check); or
     * the REST server module also enabled in the same application (see this class's javadoc).
     *
     * @param properties         this module's configuration, in particular the per-tenant API keys
     * @param auditLogProperties the core starter's configuration, to verify multi-tenancy is on
     * @param environment        checked for {@code audit.log.server.enabled} without requiring a
     *                            compile dependency on the REST server module's properties class
     * @return the configured {@link ApiKeyGrpcServerInterceptor}
     */
    @Bean
    @ConditionalOnMissingBean
    public ApiKeyGrpcServerInterceptor apiKeyGrpcServerInterceptor(AuditLogGrpcServerProperties properties,
                                                                     AuditLogProperties auditLogProperties,
                                                                     Environment environment) {
        if (properties.getApiKeys().isEmpty()) {
            throw new IllegalStateException(
                    "audit.log.grpc.enabled=true requires at least one audit.log.grpc.api-keys.<tenantId> "
                            + "entry - there is no safe default that leaves this port unauthenticated");
        }
        if (!auditLogProperties.getMultiTenancy().isEnabled()) {
            throw new IllegalStateException(
                    "audit.log.grpc.enabled=true requires audit.log.multi-tenancy.enabled=true - per-tenant "
                            + "API keys only actually confine each tenant to its own data once the core starter's "
                            + "tenant-scoped read enforcement is turned on; otherwise every key would authenticate "
                            + "a tenant identity that nothing then scopes reads by");
        }
        if (environment.getProperty("audit.log.server.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "audit.log.grpc.enabled=true and audit.log.server.enabled=true cannot both be true in the "
                            + "same application: each authenticates tenants into a different request-scoped "
                            + "context (an HttpServletRequest attribute vs. a gRPC Context value) and only one "
                            + "AuditTenantResolver bean can be active application-wide, so combining them would "
                            + "silently misroute one protocol's tenant resolution. Run REST and gRPC as separate "
                            + "deployed instances instead.");
        }
        return new ApiKeyGrpcServerInterceptor(invert(properties.getApiKeys()));
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogGrpcServer auditLogGrpcServer(AuditLogGrpcServerProperties properties,
                                                   AuditLogGrpcService auditLogGrpcService,
                                                   ApiKeyGrpcServerInterceptor apiKeyGrpcServerInterceptor) {
        return new AuditLogGrpcServer(properties.getPort(), auditLogGrpcService, apiKeyGrpcServerInterceptor);
    }

    /** {@code audit.log.grpc.api-keys} is tenantId -> secret; the interceptor needs the reverse. */
    private static Map<String, String> invert(Map<String, String> tenantIdByApiKey) {
        Map<String, String> apiKeyToTenantId = new HashMap<>();
        tenantIdByApiKey.forEach((tenantId, apiKey) -> apiKeyToTenantId.put(apiKey, tenantId));
        return apiKeyToTenantId;
    }
}
