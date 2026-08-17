package io.github.bitaron.auditlog.autoconfigure;

import io.github.bitaron.auditlog.contract.AuditLogArgumentSerializer;
import io.github.bitaron.auditlog.contract.AuditLogGenericDataGetter;
import io.github.bitaron.auditlog.contract.AuditLogLocationResolver;
import io.github.bitaron.auditlog.contract.AuditLogRecorder;
import io.github.bitaron.auditlog.contract.AuditLogTemplateResolver;
import io.github.bitaron.auditlog.contract.AuditMetricsRecorder;
import io.github.bitaron.auditlog.contract.AuditTemplateSource;
import io.github.bitaron.auditlog.contract.AuditTenantResolver;
import io.github.bitaron.auditlog.core.AuditContextResolver;
import io.github.bitaron.auditlog.core.AuditLogAspect;
import io.github.bitaron.auditlog.core.AuditLogTaskExecutor;
import io.github.bitaron.auditlog.core.AuditLogRetentionService;
import io.github.bitaron.auditlog.core.AuditLogWriter;
import io.github.bitaron.auditlog.core.AuditLogger;
import io.github.bitaron.auditlog.core.AuditSchemaValidator;
import io.github.bitaron.auditlog.core.AuditTemplateValidator;
import io.github.bitaron.auditlog.core.DatabaseAuditTemplateSource;
import io.github.bitaron.auditlog.core.DefaultAuditContextResolver;
import io.github.bitaron.auditlog.core.DefaultAuditLogRecorder;
import io.github.bitaron.auditlog.core.DefaultAuditTenantResolver;
import io.github.bitaron.auditlog.core.FreemarkerTemplateResolver;
import io.github.bitaron.auditlog.core.JacksonAuditLogArgumentSerializer;
import io.github.bitaron.auditlog.core.NoOpAuditMetricsRecorder;
import io.github.bitaron.auditlog.core.PropertiesAuditTemplateSource;
import io.github.bitaron.auditlog.properties.AuditLogProperties;
import io.github.bitaron.auditlog.query.AuditLogQueryService;
import io.github.bitaron.auditlog.query.JpaAuditLogQueryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

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

    /**
     * Deliberately not a {@code @Bean}: Spring Boot registers an {@code EntityManagerFactory},
     * not an {@code EntityManager} - {@code @PersistenceContext} injection points are handled by
     * a {@code BeanPostProcessor}, not ordinary bean lookup. Publishing a plain
     * {@code @Bean EntityManager} would add a bean type to the host application's context that it
     * never asked for: an unqualified {@code @Autowired EntityManager} in host code would
     * silently start resolving this starter's shared EntityManager instead of failing loudly (the
     * correct behavior, since the host has no {@code EntityManager} bean of its own to begin
     * with), and a host with multiple persistence units would gain an ambiguity it didn't have
     * before. Neither {@code @Bean(defaultCandidate = false)} (only de-prioritizes a bean when
     * other same-type candidates exist to prefer instead - a no-op when this would be the only
     * {@code EntityManager} in the context, the common single-persistence-unit case) nor
     * {@code @Bean(autowireCandidate = false)} (blocks autowiring entirely, including this
     * starter's own {@code @Qualifier}-based injection into {@link #auditLogWriter} etc.) gives
     * "invisible to the host's autowiring, usable by our own beans" - so instead, every internal
     * consumer below builds its own thin shared-EntityManager proxy directly from the
     * {@code EntityManagerFactory} bean Spring Boot already provides, and the type
     * {@code EntityManager} is never registered as a bean at all.
     */
    private static EntityManager sharedEntityManager(EntityManagerFactory entityManagerFactory) {
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
    public AuditTemplateSource databaseAuditTemplateSource(EntityManagerFactory entityManagerFactory) {
        return new DatabaseAuditTemplateSource(sharedEntityManager(entityManagerFactory));
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogWriter auditLogWriter(EntityManagerFactory entityManagerFactory,
                                          AuditLogTemplateResolver auditLogTemplateResolver,
                                          AuditLogArgumentSerializer auditLogArgumentSerializer,
                                          List<AuditTemplateSource> auditTemplateSources) {
        return new AuditLogWriter(sharedEntityManager(entityManagerFactory), auditLogTemplateResolver,
                auditLogArgumentSerializer, auditTemplateSources);
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

    /**
     * Only registered when {@code audit.log.multi-tenancy.enabled=true} - see
     * {@link AuditLogProperties.MultiTenancy}. With no bean registered,
     * {@link #auditContextResolver} and {@link #auditLogQueryService} both receive {@code null}/
     * {@code false} and behave exactly as they did before this feature existed.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "audit.log.multi-tenancy", name = "enabled", havingValue = "true")
    public AuditTenantResolver auditTenantResolver(AuditLogProperties auditLogProperties) {
        return new DefaultAuditTenantResolver(auditLogProperties.getHeaders().getTenantId());
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditContextResolver auditContextResolver(AuditLogProperties auditLogProperties,
                                                       ObjectProvider<AuditLogGenericDataGetter> auditLogGenericDataGetter,
                                                       ObjectProvider<AuditLogLocationResolver> auditLogLocationResolver,
                                                       ObjectProvider<AuditTenantResolver> auditTenantResolver) {
        return new DefaultAuditContextResolver(
                auditLogGenericDataGetter.getIfAvailable(),
                auditLogProperties,
                auditLogLocationResolver.getIfAvailable(),
                auditTenantResolver.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogAspect auditLogAspect(AuditContextResolver auditContextResolver, AuditLogger auditLogger) {
        return new AuditLogAspect(auditContextResolver, auditLogger);
    }

    /**
     * Programmatic write facade for callers with no {@code @Audit}-annotated method invocation to
     * intercept - see {@link AuditLogRecorder}. Depends only on {@link AuditLogger}, not the
     * aspect, so it's available even to a caller that never triggers any AOP proxying.
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditLogRecorder auditLogRecorder(AuditLogger auditLogger) {
        return new DefaultAuditLogRecorder(auditLogger);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditLogQueryService auditLogQueryService(EntityManagerFactory entityManagerFactory,
                                                       AuditLogProperties auditLogProperties,
                                                       ObjectProvider<AuditTenantResolver> auditTenantResolver) {
        return new JpaAuditLogQueryService(
                sharedEntityManager(entityManagerFactory), auditLogProperties.getQuery().getMaxPageSize(),
                auditTenantResolver.getIfAvailable(), auditLogProperties.getMultiTenancy().isEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "audit.log.schema-validation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AuditSchemaValidator auditSchemaValidator(DataSource dataSource) {
        return new AuditSchemaValidator(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "audit.log.retention", name = "enabled", havingValue = "true")
    public AuditLogRetentionService auditLogRetentionService(EntityManagerFactory entityManagerFactory,
                                                               PlatformTransactionManager transactionManager,
                                                               AuditLogProperties auditLogProperties) {
        AuditLogProperties.Retention retention = auditLogProperties.getRetention();
        if (retention.getMaxAge() == null) {
            throw new IllegalStateException(
                    "audit.log.retention.enabled=true requires audit.log.retention.max-age to be set - "
                            + "there is no safe default retention window for a compliance artifact");
        }
        return new AuditLogRetentionService(sharedEntityManager(entityManagerFactory), transactionManager,
                retention.getMaxAge(), retention.getTenantMaxAge(), retention.getCron(), retention.getBatchSize());
    }
}
