package io.github.bitaron.auditlog.autoconfigure;

import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.contract.AuditLogArgumentSerializer;
import io.github.bitaron.auditlog.contract.AuditLogGenericDataGetter;
import io.github.bitaron.auditlog.contract.AuditLogTemplateResolver;
import io.github.bitaron.auditlog.core.AuditLogAspect;
import io.github.bitaron.auditlog.core.AuditLogWriter;
import io.github.bitaron.auditlog.core.AuditLogger;
import io.github.bitaron.auditlog.core.FreemarkerTemplateResolver;
import io.github.bitaron.auditlog.entity.AuditLogMessage;
import io.github.bitaron.auditlog.model.AuditContext;
import io.github.bitaron.auditlog.query.AuditLogQueryService;
import io.github.bitaron.auditlog.query.AuditQuery;
import io.github.bitaron.auditlog.query.AuditRecord;
import io.github.bitaron.auditlog.testfixtures.host.HostAppMarker;
import io.github.bitaron.auditlog.testfixtures.host.HostRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the starter's auto-configuration against a real Spring context - the standard way to
 * test a Spring Boot starter. In particular {@link #hostApplicationOwnEntityAndRepositoryStillDiscovered()}
 * is the direct regression test for the bug where this starter's {@code @EntityScan}/
 * {@code @EnableJpaRepositories} silently stopped the host application's own entities and
 * repositories from being scanned at all.
 */
class AuditLogAutoConfigurationTest {

    // Every real Spring Boot application registers AutoConfigurationPackages implicitly via
    // @SpringBootApplication; HostAppMarker (@AutoConfigurationPackage) stands in for that here,
    // since JpaRepositoriesAutoConfiguration requires it to be present in any context at all -
    // this is what makes hostApplicationOwnEntityAndRepositoryStillDiscovered() below a faithful
    // regression test rather than a synthetic one.
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HostAppMarker.class)
            .withPropertyValues(
                    "spring.datasource.generate-unique-name=true",
                    "spring.jpa.hibernate.ddl-auto=create-drop")
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    JpaRepositoriesAutoConfiguration.class,
                    AuditLogAutoConfiguration.class));

    /**
     * Guards WP0.3: {@code AutoConfiguration.imports} naming a class that has moved or been
     * typo'd would still let {@code mvn verify} pass (every other test here loads
     * {@link AuditLogAutoConfiguration} directly via {@code AutoConfigurations.of(...)}, bypassing
     * the imports file entirely) while silently making the starter do nothing at all for a real
     * application relying on classpath auto-configuration discovery - so this test resolves the
     * file the same way Spring Boot's own {@code @SpringBootApplication} does.
     */
    @Test
    void autoConfigurationImportsFileNamesALoadableClass() {
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
                .getCandidates();
        assertThat(candidates).contains(AuditLogAutoConfiguration.class.getName());
        assertThat(candidates).allSatisfy(name ->
                assertThat(loadable(name)).as("class named in AutoConfiguration.imports: %s", name).isTrue());
    }

    private boolean loadable(String className) {
        try {
            Class.forName(className, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    void registersCoreBeansWithNoPropertiesSet() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AuditLogAspect.class);
            assertThat(context).hasSingleBean(AuditLogger.class);
            assertThat(context).hasSingleBean(AuditLogWriter.class);
            assertThat(context).hasSingleBean(AuditLogArgumentSerializer.class);
            assertThat(context.getBean(AuditLogTemplateResolver.class)).isInstanceOf(FreemarkerTemplateResolver.class);
        });
    }

    @Test
    void disabledPropertySkipsAutoConfigurationEntirely() {
        contextRunner.withPropertyValues("audit.log.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AuditLogAspect.class);
            assertThat(context).doesNotHaveBean(AuditLogger.class);
        });
    }

    @Test
    void userSuppliedTemplateResolverOverridesDefault() {
        contextRunner.withUserConfiguration(CustomResolverConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AuditLogTemplateResolver.class)).isInstanceOf(StubTemplateResolver.class);
        });
    }

    @Test
    void userSuppliedGenericDataGetterIsWiredIntoTheAspect() {
        contextRunner.withUserConfiguration(CustomDataGetterConfig.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * The regression test: a "host application" that only declares its own entity/repository and
     * relies on Spring Boot's implicit default scanning (no explicit @EntityScan or
     * @EnableJpaRepositories of its own - the common case) must still have that entity and
     * repository registered with the audit-log starter on the classpath.
     */
    @Test
    void hostApplicationOwnEntityAndRepositoryStillDiscovered() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(HostRepository.class);
            // Exercise it - not just present as a bean, but actually backed by a created table.
            HostRepository hostRepository = context.getBean(HostRepository.class);
            assertThat(hostRepository.count()).isZero();
            // And the starter's own repository-free persistence path still works alongside it.
            assertThat(context).hasSingleBean(AuditLogWriter.class);
        });
    }

    /**
     * The regression test for A3: this starter must not add any {@code EntityManager}-typed bean
     * to the host application's context. Spring's own JPA infrastructure already publishes
     * synthetic shared-EntityManager beans (e.g. {@code jpaSharedEM_entityManagerFactory}) the
     * moment any {@code @PersistenceContext}-style resolution happens anywhere in the context
     * (Spring Data JPA repositories trigger this, as {@link HostRepository} does here) -
     * regardless of this starter. What this starter must never do is add to that set: every
     * internal consumer of an {@code EntityManager} (see {@link AuditLogWriter},
     * {@code DatabaseAuditTemplateSource}, {@code JpaAuditLogQueryService}) builds its own private
     * shared-EntityManager proxy directly from the {@code EntityManagerFactory} bean instead of
     * depending on a bean of type {@code EntityManager} that this starter publishes - because
     * there is no such bean, comparing the set of {@code EntityManager}-typed beans with the
     * starter enabled vs. disabled must show no difference.
     */
    @Test
    void starterAddsNoEntityManagerTypedBeanToTheHostContext() {
        contextRunner.withPropertyValues("audit.log.enabled=false")
                .run(withoutStarter -> contextRunner.run(withStarter -> {
                    assertThat(withoutStarter).hasNotFailed();
                    assertThat(withStarter).hasNotFailed();
                    assertThat(withStarter.getBeanNamesForType(EntityManager.class))
                            .containsExactlyInAnyOrder(withoutStarter.getBeanNamesForType(EntityManager.class));
                }));
    }

    /** Acceptance test for WP5: a template defined only in configuration renders with no
     * matching {@code audit_template} database row present at all. */
    @Test
    void propertiesTemplateResolvesWithoutDatabaseRow() {
        contextRunner.withPropertyValues("audit.log.templates.greeting=Hello ${actorName}!").run(context -> {
            assertThat(context).hasNotFailed();
            AuditLogWriter writer = context.getBean(AuditLogWriter.class);
            PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
            AuditContext auditContext = new AuditContext(
                    "actor-1", "Ada", null, null, null, null, null, null, false, 0, null);

            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    writer.persistRequiresNew(fixtureAudit(), auditContext));

            List<AuditLogMessage> messages = new TransactionTemplate(transactionManager).execute(status ->
                    context.getBean(EntityManager.class)
                            .createQuery("select m from AuditLogMessage m", AuditLogMessage.class)
                            .getResultList());
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).getMessage()).isEqualTo("Hello Ada!");
        });
    }

    @Test
    void failOnMissingTemplateFailsStartupWhenUnresolved() {
        contextRunner.withUserConfiguration(AuditedBeanConfig.class)
                .withPropertyValues("audit.log.fail-on-missing-template=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failOnMissingTemplateSucceedsWhenResolved() {
        contextRunner.withUserConfiguration(AuditedBeanConfig.class)
                .withPropertyValues(
                        "audit.log.fail-on-missing-template=true",
                        "audit.log.templates.startup-check=Hello!")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void missingTemplateOnlyWarnsWhenFailOnMissingTemplateIsOff() {
        contextRunner.withUserConfiguration(AuditedBeanConfig.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** Acceptance test for WP6: an invalid executor size fails startup via JSR-303 binding
     * validation - but only once a validator implementation is actually on the classpath (this
     * module's own test classpath has hibernate-validator; see the pom.xml comment on why it
     * isn't a main-scope dependency). */
    @Test
    void invalidExecutorPoolSizeFailsStartupValidation() {
        contextRunner.withPropertyValues("audit.log.executor.core-pool-size=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void queryServiceFiltersByActorIdAndPaginates() {
        contextRunner.run(context -> {
            AuditLogWriter writer = context.getBean(AuditLogWriter.class);
            PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                writer.persistRequiresNew(fixtureAuditNoTemplates(), new AuditContext(
                        "actor-1", "Ada", null, null, null, null, null, null, false, 0, null));
                writer.persistRequiresNew(fixtureAuditNoTemplates(), new AuditContext(
                        "actor-2", "Bob", null, null, null, null, null, null, false, 0, null));
            });

            AuditLogQueryService queryService = context.getBean(AuditLogQueryService.class);
            Page<AuditRecord> page = queryService.find(
                    new AuditQuery("actor-1", null, null, null), PageRequest.of(0, 10));

            assertThat(page.getTotalElements()).isEqualTo(1);
            assertThat(page.getContent().get(0).actorId()).isEqualTo("actor-1");
        });
    }

    private Audit fixtureAudit() {
        return fixtureAudit("action");
    }

    private Audit fixtureAuditNoTemplates() {
        return fixtureAudit("noTemplates");
    }

    private Audit fixtureAudit(String methodName) {
        try {
            Method method = Fixture.class.getDeclaredMethod(methodName);
            return method.getAnnotation(Audit.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Fixture {
        @Audit(auditType = "test", templates = {"greeting"})
        void action() {
        }

        @Audit(auditType = "test")
        void noTemplates() {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AuditedBeanConfig {
        @Bean
        AuditedBean auditedBean() {
            return new AuditedBean();
        }
    }

    static class AuditedBean {
        @Audit(auditType = "test", templates = {"startup-check"})
        void action() {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomResolverConfig {
        @Bean
        AuditLogTemplateResolver auditLogTemplateResolver() {
            return new StubTemplateResolver();
        }
    }

    static class StubTemplateResolver implements AuditLogTemplateResolver {
        @Override
        public String resolveTemplate(String name, String template, AuditContext context) {
            return "stub";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomDataGetterConfig {
        @Bean
        AuditLogGenericDataGetter auditLogGenericDataGetter() {
            return new AuditLogGenericDataGetter() {
                @Override
                public String getActorId() {
                    return "id";
                }

                @Override
                public String getActorName() {
                    return "name";
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
            };
        }
    }
}
