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
     * Fails startup rather than running unauthenticated/unscoped when this module is enabled with
     * no per-tenant keys configured, or with the core starter's tenant-scoped read enforcement
     * turned off. The latter check exists because per-tenant API keys only actually confine each
     * tenant to its own data once {@code audit.log.multi-tenancy.enabled=true} makes
     * {@code JpaAuditLogQueryService} apply the resolved tenant to every read - without it, every
     * key would authenticate a distinct tenant identity that nothing then scopes reads by.
     *
     * @param properties       this module's configuration, in particular the per-tenant API keys
     * @param auditLogProperties the core starter's configuration, to verify multi-tenancy is on
     * @return the registered {@link ApiKeyAuthFilter}, covering every path under this module,
     * ordered ahead of every other filter so an unauthenticated request never reaches
     * {@link DispatcherServlet} at all
     */
    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<Filter> auditLogServerApiKeyFilter(AuditLogServerProperties properties,
                                                                       AuditLogProperties auditLogProperties) {
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
