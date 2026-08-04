package io.github.bitaron.auditLog.config.spring;

import io.github.bitaron.auditLog.contract.AuditLogGenericDataGetter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Registers a {@link AuditLogGenericDataGetter} backed by {@link SecurityContextHolder} when
 * Spring Security is present and no other actor-resolution bean is configured, so the default
 * actor identity comes from verified authentication rather than a spoofable HTTP header.
 * <p>
 * Kept in a separate {@code @Configuration} (rather than a plain {@code @Bean} method on the
 * auto-configuration class) so that {@code @ConditionalOnClass} can prevent this class from ever
 * being loaded - and Spring Security types resolved - in an application that doesn't have Spring
 * Security on its classpath at all.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(SecurityContextHolder.class)
class AuditLogSecurityContextConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditLogGenericDataGetter.class)
    public AuditLogGenericDataGetter securityContextAuditLogGenericDataGetter() {
        return new SecurityContextAuditLogGenericDataGetter();
    }

    private static final class SecurityContextAuditLogGenericDataGetter implements AuditLogGenericDataGetter {

        @Override
        public String getActorId() {
            return currentPrincipalName();
        }

        @Override
        public String getActorName() {
            return currentPrincipalName();
        }

        @Override
        public String getClientLocation() {
            return "";
        }

        @Override
        public String getClientIp() {
            return "";
        }

        @Override
        public String getUserAgent() {
            return "";
        }

        private String currentPrincipalName() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) {
                return "";
            }
            return authentication.getName();
        }
    }
}
