package io.github.bitaron.auditlog.server;

import io.github.bitaron.auditlog.autoconfigure.AuditLogAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP13 acceptance tests for {@link AuditLogServerAutoConfiguration}'s conditional wiring - see
 * {@link AuditLogServerIntegrationTest} for the actual HTTP-level behavior once it's active.
 */
class AuditLogServerAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ServerHostAppMarker.class)
            .withPropertyValues(
                    "spring.datasource.generate-unique-name=true",
                    "spring.jpa.hibernate.ddl-auto=create-drop")
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    DataJpaRepositoriesAutoConfiguration.class,
                    AopAutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    AuditLogAutoConfiguration.class,
                    AuditLogServerAutoConfiguration.class));

    @Test
    void disabledByDefaultRegistersNeitherController() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AuditIngestController.class);
            assertThat(context).doesNotHaveBean(AuditQueryController.class);
        });
    }

    @Test
    void enabledWithoutApiKeysFailsStartup() {
        contextRunner.withPropertyValues(
                        "audit.log.server.enabled=true",
                        "audit.log.multi-tenancy.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    /** WP16: per-tenant keys without the core starter's tenant-scoped read enforcement would
     * authenticate a tenant without ever actually confining reads to it - refused outright. */
    @Test
    void enabledWithApiKeysButMultiTenancyDisabledFailsStartup() {
        contextRunner.withPropertyValues(
                        "audit.log.server.enabled=true",
                        "audit.log.server.api-keys.tenant-a=a-secret")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledWithApiKeysAndMultiTenancyRegistersBothControllers() {
        contextRunner.withPropertyValues(
                        "audit.log.server.enabled=true",
                        "audit.log.multi-tenancy.enabled=true",
                        "audit.log.server.api-keys.tenant-a=a-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuditIngestController.class);
                    assertThat(context).hasSingleBean(AuditQueryController.class);
                    assertThat(context.getBean(io.github.bitaron.auditlog.contract.AuditTenantResolver.class))
                            .isInstanceOf(ApiKeyAuditTenantResolver.class);
                });
    }
}
