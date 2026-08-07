package io.github.bitaron.auditlog.client.autoconfigure;

import io.github.bitaron.auditlog.client.AuditLogHttpClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Auto-configuration for {@link AuditLogHttpClient}. {@code audit-log-java-client} itself
 * deliberately registers no beans and depends on nothing beyond {@code spring-web} - it's meant to
 * be usable outside Spring Boot entirely (see its javadoc). This module is the opt-in Spring Boot
 * integration on top of it: a separate artifact rather than code inside
 * {@code audit-log-java-client} itself, so a non-Spring-Boot consumer of the plain client never
 * gets {@code spring-boot-autoconfigure} forced onto its classpath.
 * <p>
 * Gated by {@code audit.log.client.enabled} with no {@code matchIfMissing} - unlike the core
 * starter's {@code AuditLogAutoConfiguration} (on by default), a bean pointed at a specific
 * external URL and holding a secret API key must not be registered just because this module
 * happens to be on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(AuditLogHttpClient.class)
@ConditionalOnProperty(prefix = "audit.log.client", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AuditLogClientProperties.class)
public class AuditLogClientAutoConfiguration {

    /**
     * The {@link AuditLogHttpClient#AuditLogHttpClient(RestClient.Builder, String, String)}
     * overload (not the 2-arg one) is what makes this bean's timeouts actually configurable - it's
     * the exact seam that constructor was added for.
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditLogHttpClient auditLogHttpClient(AuditLogClientProperties properties) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException(
                    "audit.log.client.enabled=true requires audit.log.client.base-url to be set - "
                            + "there is no sensible default for which server to talk to");
        }
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.getHttp().getConnectTimeout())
                .withReadTimeout(properties.getHttp().getReadTimeout());
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        return new AuditLogHttpClient(RestClient.builder().requestFactory(requestFactory),
                properties.getBaseUrl(), properties.getApiKey());
    }
}
