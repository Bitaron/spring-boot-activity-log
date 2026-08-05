package io.github.bitaron.auditlog.server;

import io.github.bitaron.auditlog.autoconfigure.AuditLogAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
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
                    JpaRepositoriesAutoConfiguration.class,
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
    void enabledWithoutApiKeyFailsStartup() {
        contextRunner.withPropertyValues("audit.log.server.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledWithApiKeyRegistersBothControllers() {
        contextRunner.withPropertyValues(
                        "audit.log.server.enabled=true",
                        "audit.log.server.api-key=a-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuditIngestController.class);
                    assertThat(context).hasSingleBean(AuditQueryController.class);
                });
    }
}
