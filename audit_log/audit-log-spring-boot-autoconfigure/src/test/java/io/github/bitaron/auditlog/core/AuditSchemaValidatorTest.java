package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.autoconfigure.AuditLogAutoConfiguration;
import io.github.bitaron.auditlog.testfixtures.host.HostAppMarker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP10 acceptance tests for {@link AuditSchemaValidator}: startup fails loudly, with an
 * actionable message, when the starter's required tables are missing - rather than the previous
 * behavior of no check at all and a buried runtime warning on the first audited call.
 */
class AuditSchemaValidatorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HostAppMarker.class)
            .withPropertyValues("spring.datasource.generate-unique-name=true")
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    DataJpaRepositoriesAutoConfiguration.class,
                    AuditLogAutoConfiguration.class));

    @Test
    void failsStartupWithAnActionableMessageWhenTablesAreMissing() {
        contextRunner.withPropertyValues("spring.jpa.hibernate.ddl-auto=none").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("audit_log")
                    .hasMessageContaining("V2__audit_log_v2.sql")
                    .hasMessageContaining("audit.log.schema-validation.enabled=false");
        });
    }

    @Test
    void startsCleanlyWhenTablesArePresent() {
        contextRunner.withPropertyValues("spring.jpa.hibernate.ddl-auto=create-drop")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void disablingTheCheckSkipsItEvenWithTablesMissing() {
        contextRunner.withPropertyValues(
                        "spring.jpa.hibernate.ddl-auto=none",
                        "audit.log.schema-validation.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AuditSchemaValidator.class);
                });
    }
}
