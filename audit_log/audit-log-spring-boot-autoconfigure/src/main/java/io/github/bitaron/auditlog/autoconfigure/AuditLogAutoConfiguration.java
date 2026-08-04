package io.github.bitaron.auditlog.autoconfigure;

import io.github.bitaron.auditlog.contract.AuditLogArgumentSerializer;
import io.github.bitaron.auditlog.contract.AuditLogGenericDataGetter;
import io.github.bitaron.auditlog.contract.AuditLogLocationResolver;
import io.github.bitaron.auditlog.contract.AuditLogTemplateResolver;
import io.github.bitaron.auditlog.contract.AuditMetricsRecorder;
import io.github.bitaron.auditlog.contract.AuditTemplateSource;
import io.github.bitaron.auditlog.core.AuditContextResolver;
import io.github.bitaron.auditlog.core.AuditLogAspect;
import io.github.bitaron.auditlog.core.AuditLogTaskExecutor;
import io.github.bitaron.auditlog.core.AuditLogWriter;
import io.github.bitaron.auditlog.core.AuditLogger;
import io.github.bitaron.auditlog.core.AuditTemplateValidator;
import io.github.bitaron.auditlog.core.DatabaseAuditTemplateSource;
import io.github.bitaron.auditlog.core.DefaultAuditContextResolver;
import io.github.bitaron.auditlog.core.FreemarkerTemplateResolver;
import io.github.bitaron.auditlog.core.JacksonAuditLogArgumentSerializer;
import io.github.bitaron.auditlog.core.NoOpAuditMetricsRecorder;
import io.github.bitaron.auditlog.core.PropertiesAuditTemplateSource;
import io.github.bitaron.auditlog.properties.AuditLogProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
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

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Auto-configuration for the audit-log starter.
 * <p>
 * Every collaborator is wired through {@code @Bean} method parameters rather than field
 * injection on this class (constructor-time circular references were a real bug in a previous
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
@Import({AuditLogEntityScanRegistrar.class, AuditLogSecurityContextConfiguration.class, AuditLogMicrometerConfiguration.class})
public class AuditLogAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditLogTemplateResolver auditLogTemplateResolver(AuditLogProperties auditLogProperties) {
        return new FreemarkerTemplateResolver(auditLogProperties.getMaxTemplateCacheSize());
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogArgumentSerializer auditLogArgumentSerializer(AuditLogProperties auditLogProperties) {
        return new JacksonAuditLogArgumentSerializer(auditLogProperties);
    }

    /** Default when Micrometer isn't on the classpath; see {@link AuditLogMicrometerConfiguration}. */
    @Bean
    @ConditionalOnMissingBean
    public AuditMetricsRecorder auditMetricsRecorder() {
        return new NoOpAuditMetricsRecorder();
    }

    @Bean(name = "auditLogTaskExecutor")
    @ConditionalOnMissingBean(name = "auditLogTaskExecutor")
    public Executor auditLogTaskExecutor(AuditLogProperties auditLogProperties, AuditMetricsRecorder auditMetricsRecorder) {
        AuditLogProperties.Executor executorProperties = auditLogProperties.getExecutor();
        // Deliberately not wired via @EnableAsync/@Async: turning on async proxying is a
        // context-wide, consumer-visible behavior change this starter should not impose. Audit
        // writes are dispatched to this executor directly instead - see AuditLogger. Its own
        // DisposableBean#destroy (not an inferred/declared destroyMethod) handles shutdown, since
        // it needs to report exactly how many queued writes were dropped - see AuditLogTaskExecutor.
        return new AuditLogTaskExecutor(
                executorProperties.getCorePoolSize(),
                executorProperties.getMaxPoolSize(),
                executorProperties.getQueueCapacity(),
                executorProperties.getAwaitTerminationSeconds(),
                auditMetricsRecorder);
    }

    @Bean
    @ConditionalOnMissingBean(name = "auditLogEntityManager")
    public EntityManager auditLogEntityManager(EntityManagerFactory entityManagerFactory) {
        return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    }

    /** Tried before {@link #databaseAuditTemplateSource} - see {@link PropertiesAuditTemplateSource}. */
    @Bean
    @ConditionalOnMissingBean(name = "propertiesAuditTemplateSource")
    public AuditTemplateSource propertiesAuditTemplateSource(AuditLogProperties auditLogProperties) {
        return new PropertiesAuditTemplateSource(auditLogProperties);
    }

    @Bean
    @ConditionalOnMissingBean(name = "databaseAuditTemplateSource")
    public AuditTemplateSource databaseAuditTemplateSource(EntityManager auditLogEntityManager) {
        return new DatabaseAuditTemplateSource(auditLogEntityManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogWriter auditLogWriter(EntityManager auditLogEntityManager,
                                          AuditLogTemplateResolver auditLogTemplateResolver,
                                          AuditLogArgumentSerializer auditLogArgumentSerializer,
                                          List<AuditTemplateSource> auditTemplateSources) {
        return new AuditLogWriter(auditLogEntityManager, auditLogTemplateResolver, auditLogArgumentSerializer,
                auditTemplateSources);
    }

    @Bean
    @ConditionalOnProperty(prefix = "audit.log", name = "fail-on-missing-template", havingValue = "true")
    public AuditTemplateValidator auditTemplateValidator(ConfigurableListableBeanFactory beanFactory,
                                                           List<AuditTemplateSource> auditTemplateSources) {
        return new AuditTemplateValidator(beanFactory, auditTemplateSources);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogger auditLogger(AuditLogWriter auditLogWriter, Executor auditLogTaskExecutor,
                                    AuditMetricsRecorder auditMetricsRecorder, AuditLogProperties auditLogProperties) {
        return new AuditLogger(auditLogWriter, auditLogTaskExecutor, auditMetricsRecorder, auditLogProperties.getMode());
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditContextResolver auditContextResolver(AuditLogProperties auditLogProperties,
                                                       ObjectProvider<AuditLogGenericDataGetter> auditLogGenericDataGetter,
                                                       ObjectProvider<AuditLogLocationResolver> auditLogLocationResolver) {
        return new DefaultAuditContextResolver(
                auditLogGenericDataGetter.getIfAvailable(),
                auditLogProperties,
                auditLogLocationResolver.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogAspect auditLogAspect(AuditContextResolver auditContextResolver, AuditLogger auditLogger) {
        return new AuditLogAspect(auditContextResolver, auditLogger);
    }
}
