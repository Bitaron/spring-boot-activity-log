package io.github.bitaron.auditLog.config.spring;

import io.github.bitaron.auditLog.contract.AuditLogArgumentSerializer;
import io.github.bitaron.auditLog.contract.AuditLogGenericDataGetter;
import io.github.bitaron.auditLog.contract.AuditLogLocationResolver;
import io.github.bitaron.auditLog.contract.AuditLogTemplateResolver;
import io.github.bitaron.auditLog.core.AuditLogAspect;
import io.github.bitaron.auditLog.core.AuditLogWriter;
import io.github.bitaron.auditLog.core.AuditLogger;
import io.github.bitaron.auditLog.core.FreemarkerTemplateResolver;
import io.github.bitaron.auditLog.core.JacksonAuditLogArgumentSerializer;
import io.github.bitaron.auditLog.properties.AuditLogProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Auto-configuration for the audit-log starter.
 * <p>
 * Every collaborator is wired through {@code @Bean} method parameters rather than field
 * injection on this class (constructor-time circular references were a real bug in the previous
 * version, since {@link AuditLogTemplateResolver} was both a field autowired here and a
 * {@code @Bean} produced by this same class), and every bean is
 * {@code @ConditionalOnMissingBean} so a consuming application can override any piece of it.
 * <p>
 * This starter deliberately does not use Spring Data JPA repositories or {@code @EntityScan} in
 * the naive way: see {@link AuditLogEntityScanRegistrar} for how its own JPA entities are added
 * to - never substituted for - the host application's entity scanning, and {@link AuditLogWriter}
 * for why persistence goes through a plain {@link EntityManager} instead of
 * {@code @EnableJpaRepositories} (which would silently disable Spring Boot's own repository
 * scanning for the host application's own repositories).
 */
@AutoConfiguration
@AutoConfigureAfter(HibernateJpaAutoConfiguration.class)
@ConditionalOnClass(EntityManager.class)
@ConditionalOnProperty(prefix = "audit.log", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuditLogProperties.class)
@Import({AuditLogEntityScanRegistrar.class, AuditLogSecurityContextConfiguration.class})
public class AuditLogSpringBootAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public AuditLogTemplateResolver auditLogTemplateResolver() {
        return new FreemarkerTemplateResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogArgumentSerializer auditLogArgumentSerializer(AuditLogProperties auditLogProperties) {
        return new JacksonAuditLogArgumentSerializer(auditLogProperties);
    }

    @Bean(name = "auditLogTaskExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "auditLogTaskExecutor")
    public Executor auditLogTaskExecutor(AuditLogProperties auditLogProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(auditLogProperties.getExecutor().getCorePoolSize());
        executor.setMaxPoolSize(auditLogProperties.getExecutor().getMaxPoolSize());
        executor.setQueueCapacity(auditLogProperties.getExecutor().getQueueCapacity());
        executor.setThreadNamePrefix("audit-log-");
        // Deliberately not wired via @EnableAsync/@Async: turning on async proxying is a
        // context-wide, consumer-visible behavior change this starter should not impose. Audit
        // writes are dispatched to this executor directly instead - see AuditLogger.
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean(name = "auditLogEntityManager")
    public EntityManager auditLogEntityManager(EntityManagerFactory entityManagerFactory) {
        return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogWriter auditLogWriter(EntityManager auditLogEntityManager,
                                          AuditLogTemplateResolver auditLogTemplateResolver,
                                          AuditLogArgumentSerializer auditLogArgumentSerializer) {
        return new AuditLogWriter(auditLogEntityManager, auditLogTemplateResolver, auditLogArgumentSerializer);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogger auditLogger(AuditLogWriter auditLogWriter, Executor auditLogTaskExecutor) {
        return new AuditLogger(auditLogWriter, auditLogTaskExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogAspect auditLogAspect(AuditLogProperties auditLogProperties,
                                          ObjectProvider<AuditLogGenericDataGetter> auditLogGenericDataGetter,
                                          ObjectProvider<AuditLogLocationResolver> auditLogLocationResolver,
                                          AuditLogger auditLogger) {
        return new AuditLogAspect(auditLogProperties,
                auditLogGenericDataGetter.getIfAvailable(),
                auditLogLocationResolver.getIfAvailable(),
                auditLogger);
    }
}
