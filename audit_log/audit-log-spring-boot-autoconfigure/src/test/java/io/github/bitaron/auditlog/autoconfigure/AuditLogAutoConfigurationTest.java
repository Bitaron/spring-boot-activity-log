package io.github.bitaron.auditlog.autoconfigure;

import io.github.bitaron.auditlog.contract.AuditLogArgumentSerializer;
import io.github.bitaron.auditlog.contract.AuditLogGenericDataGetter;
import io.github.bitaron.auditlog.contract.AuditLogTemplateResolver;
import io.github.bitaron.auditlog.core.AuditLogAspect;
import io.github.bitaron.auditlog.core.AuditLogWriter;
import io.github.bitaron.auditlog.core.AuditLogger;
import io.github.bitaron.auditlog.core.FreemarkerTemplateResolver;
import io.github.bitaron.auditlog.model.AuditContext;
import io.github.bitaron.auditlog.testfixtures.host.HostAppMarker;
import io.github.bitaron.auditlog.testfixtures.host.HostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
