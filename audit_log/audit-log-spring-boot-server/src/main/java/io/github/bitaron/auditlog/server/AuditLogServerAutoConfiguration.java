package io.github.bitaron.auditlog.server;

import io.github.bitaron.auditlog.autoconfigure.AuditLogAutoConfiguration;
import io.github.bitaron.auditlog.contract.AuditLogRecorder;
import io.github.bitaron.auditlog.contract.AuditTenantResolver;
import io.github.bitaron.auditlog.properties.AuditLogProperties;
import io.github.bitaron.auditlog.query.AuditLogQueryService;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.HashMap;
import java.util.Map;

/**
 * Auto-configuration for the optional REST ingestion/query server.
 * <p>
 * Gated by {@code audit.log.server.enabled} with no {@code matchIfMissing} - unlike the core
 * starter's {@code AuditLogAutoConfiguration} (on by default), depending on this module must not
 * silently expose HTTP endpoints; a consumer opts in explicitly.
 * <p>
 * {@code @AutoConfigureBefore(AuditLogAutoConfiguration.class)} (WP16): this module's
 * {@link ApiKeyAuditTenantResolver} bean must be registered before the core starter's own
 * {@code @ConditionalOnMissingBean} default gets a chance to run, so the per-tenant-API-key-backed
 * resolver - not the spoofable header-based default - is what every
 * {@code DefaultAuditContextResolver}/{@code JpaAuditLogQueryService} in the application resolves
 * against once this module is enabled.
 * <p>
 * Registers a {@link ProtobufHttpMessageConverter} bean (Spring's built-in one, supporting
 * {@code application/x-protobuf} and, for debugging/curl convenience, {@code application/json})
 * rather than hand-writing (de)serialization - Spring Boot's auto-configured
 * {@code RequestMappingHandlerAdapter} picks up any {@code HttpMessageConverter} bean in the
 * context automatically.
 * <p>
 * <b>Cannot coexist with the gRPC server module (WP18) in the same application</b> - see
 * {@link #auditLogServerApiKeyFilter}'s fail-fast check and
 * {@code AuditLogGrpcServerAutoConfiguration}'s javadoc for why.
 */
@AutoConfiguration
@AutoConfigureBefore(AuditLogAutoConfiguration.class)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "audit.log.server", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AuditLogServerProperties.class)
public class AuditLogServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProtobufHttpMessageConverter protobufHttpMessageConverter() {
        return new ProtobufHttpMessageConverter();
    }

    /**
     * Always the per-tenant-API-key-backed resolver, never conditioned on
     * {@code audit.log.multi-tenancy.enabled} the way the core starter's default is - see
     * {@link #auditLogServerApiKeyFilter} for why this module requires that flag whenever it's
     * enabled anyway. A consumer wanting a different resolver overrides this bean, same as any
     * other {@code @ConditionalOnMissingBean} SPI default in this starter.
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditTenantResolver auditTenantResolver() {
        return new ApiKeyAuditTenantResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditIngestController auditIngestController(AuditLogRecorder auditLogRecorder,
                                                         AuditTenantResolver auditTenantResolver) {
        return new AuditIngestController(auditLogRecorder, auditTenantResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditQueryController auditQueryController(AuditLogQueryService auditLogQueryService) {
        return new AuditQueryController(auditLogQueryService);
    }

    /**
     * {@code @RestControllerAdvice} is a component-scanning stereotype - without registering it as
     * a bean explicitly here, whether it's ever picked up depends entirely on whether the host
     * application's own {@code @SpringBootApplication} base package happens to cover
     * {@code io.github.bitaron.auditlog.server}. Every one of this module's own tests before WP17
     * had a {@code TestServerApplication}/{@code ClientTestServerApplication} that either did (and
     * so passed by coincidence) or never exercised a caller-error path at all - a real host
     * application's base package essentially never overlaps with this library's, so every
     * {@link IllegalArgumentException}/{@link IllegalStateException} this module's controllers
     * throw would silently surface as an unhelpful {@code 500} instead of the documented
     * {@code 400}. Caught empirically writing {@code AuditLogHttpClientErrorHandlingTest}, whose
     * {@code ClientTestServerApplication} lives in {@code io.github.bitaron.auditlog.client}.
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditServerExceptionHandler auditServerExceptionHandler() {
        return new AuditServerExceptionHandler();
    }

    /**
     * Fails startup rather than running unauthenticated/unscoped/conflicting when this module is
     * enabled with: no per-tenant keys configured; the core starter's tenant-scoped read
     * enforcement turned off (per-tenant API keys only actually confine each tenant to its own
     * data once {@code audit.log.multi-tenancy.enabled=true} makes {@code JpaAuditLogQueryService}
     * apply the resolved tenant to every read - without it, every key would authenticate a
     * distinct tenant identity that nothing then scopes reads by); or the gRPC server module
     * (WP18, {@code audit-log-spring-boot-grpc-server}) also enabled in the same application - see
     * {@code AuditLogGrpcServerAutoConfiguration}'s javadoc for why the two cannot coexist.
     *
     * @param properties       this module's configuration, in particular the per-tenant API keys
     * @param auditLogProperties the core starter's configuration, to verify multi-tenancy is on
     * @param environment      checked for {@code audit.log.grpc.enabled} without requiring a
     *                         compile dependency on the gRPC server module's properties class
     * @return the registered {@link ApiKeyAuthFilter}, covering every path under this module,
     * ordered ahead of every other filter so an unauthenticated request never reaches
     * {@link DispatcherServlet} at all
     */
    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<Filter> auditLogServerApiKeyFilter(AuditLogServerProperties properties,
                                                                       AuditLogProperties auditLogProperties,
                                                                       Environment environment) {
        if (properties.getApiKeys().isEmpty()) {
            throw new IllegalStateException(
                    "audit.log.server.enabled=true requires at least one audit.log.server.api-keys.<tenantId> "
                            + "entry - there is no safe default that leaves audit ingestion/read endpoints open");
        }
        if (!auditLogProperties.getMultiTenancy().isEnabled()) {
            throw new IllegalStateException(
                    "audit.log.server.enabled=true requires audit.log.multi-tenancy.enabled=true - per-tenant "
                            + "API keys only actually confine each tenant to its own data once the core starter's "
                            + "tenant-scoped read enforcement is turned on; otherwise every key would authenticate "
                            + "a tenant identity that nothing then scopes reads by");
        }
        if (environment.getProperty("audit.log.grpc.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "audit.log.server.enabled=true and audit.log.grpc.enabled=true cannot both be true in the "
                            + "same application: each authenticates tenants into a different request-scoped "
                            + "context (an HttpServletRequest attribute vs. a gRPC Context value) and only one "
                            + "AuditTenantResolver bean can be active application-wide, so combining them would "
                            + "silently misroute one protocol's tenant resolution. Run REST and gRPC as separate "
                            + "deployed instances instead.");
        }
        FilterRegistrationBean<Filter> registration =
                new FilterRegistrationBean<>(new ApiKeyAuthFilter(invert(properties.getApiKeys())));
        registration.addUrlPatterns("/audit-log/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** {@code audit.log.server.api-keys} is tenantId -> secret; the filter needs the reverse. */
    private static Map<String, String> invert(Map<String, String> tenantIdByApiKey) {
        Map<String, String> apiKeyToTenantId = new HashMap<>();
        tenantIdByApiKey.forEach((tenantId, apiKey) -> apiKeyToTenantId.put(apiKey, tenantId));
        return apiKeyToTenantId;
    }
}
