package io.github.bitaron.auditlog.server;

import io.github.bitaron.auditlog.contract.AuditLogRecorder;
import io.github.bitaron.auditlog.query.AuditLogQueryService;
import jakarta.servlet.Filter;
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

/**
 * Auto-configuration for the optional REST ingestion/query server.
 * <p>
 * Gated by {@code audit.log.server.enabled} with no {@code matchIfMissing} - unlike the core
 * starter's {@code AuditLogAutoConfiguration} (on by default), depending on this module must not
 * silently expose HTTP endpoints; a consumer opts in explicitly.
 * <p>
 * Registers a {@link ProtobufHttpMessageConverter} bean (Spring's built-in one, supporting
 * {@code application/x-protobuf} and, for debugging/curl convenience, {@code application/json})
 * rather than hand-writing (de)serialization - Spring Boot's auto-configured
 * {@code RequestMappingHandlerAdapter} picks up any {@code HttpMessageConverter} bean in the
 * context automatically.
 */
@AutoConfiguration
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "audit.log.server", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AuditLogServerProperties.class)
public class AuditLogServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProtobufHttpMessageConverter protobufHttpMessageConverter() {
        return new ProtobufHttpMessageConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditIngestController auditIngestController(AuditLogRecorder auditLogRecorder) {
        return new AuditIngestController(auditLogRecorder);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditQueryController auditQueryController(AuditLogQueryService auditLogQueryService) {
        return new AuditQueryController(auditLogQueryService);
    }

    /**
     * Fails startup rather than running unauthenticated when no API key is configured - see
     * {@link AuditLogServerProperties#apiKey}. Ordered ahead of every other filter so an
     * unauthenticated request never reaches {@link DispatcherServlet} at all.
     *
     * @param properties this module's configuration, in particular the required API key
     * @return the registered {@link ApiKeyAuthFilter}, covering every path under this module
     */
    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<Filter> auditLogServerApiKeyFilter(AuditLogServerProperties properties) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "audit.log.server.enabled=true requires audit.log.server.api-key to be set - "
                            + "there is no safe default that leaves audit ingestion/read endpoints open");
        }
        FilterRegistrationBean<Filter> registration =
                new FilterRegistrationBean<>(new ApiKeyAuthFilter(properties.getApiKey()));
        registration.addUrlPatterns("/audit-log/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
