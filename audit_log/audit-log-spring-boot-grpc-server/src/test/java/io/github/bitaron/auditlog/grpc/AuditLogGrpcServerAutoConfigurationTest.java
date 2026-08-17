package io.github.bitaron.auditlog.grpc;

import io.github.bitaron.auditlog.autoconfigure.AuditLogAutoConfiguration;
import io.github.bitaron.auditlog.contract.AuditTenantResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP18 acceptance tests for {@link AuditLogGrpcServerAutoConfiguration}'s conditional wiring - see
 * {@link AuditLogGrpcServerIntegrationTest} for the actual RPC-level behavior once it's active.
 * {@code audit.log.grpc.port=0} throughout - an OS-assigned ephemeral port, so these tests (which
 * do start a real listener via {@link AuditLogGrpcServer#afterPropertiesSet()}) never collide with
 * each other or with a fixed port a real deployment might use.
 */
class AuditLogGrpcServerAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GrpcServerHostAppMarker.class)
            .withPropertyValues(
                    "spring.datasource.generate-unique-name=true",
                    "spring.jpa.hibernate.ddl-auto=create-drop",
                    "audit.log.grpc.port=0")
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    DataJpaRepositoriesAutoConfiguration.class,
                    AuditLogAutoConfiguration.class,
                    AuditLogGrpcServerAutoConfiguration.class));

    @Test
    void disabledByDefaultRegistersNoGrpcServer() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AuditLogGrpcServer.class);
        });
    }

    @Test
    void enabledWithoutApiKeysFailsStartup() {
        contextRunner.withPropertyValues(
                        "audit.log.grpc.enabled=true",
                        "audit.log.multi-tenancy.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledWithApiKeysButMultiTenancyDisabledFailsStartup() {
        contextRunner.withPropertyValues(
                        "audit.log.grpc.enabled=true",
                        "audit.log.grpc.api-keys.tenant-a=a-secret")
                .run(context -> assertThat(context).hasFailed());
    }

    /** WP18: this module and the REST server module cannot coexist in the same application - see
     * {@link AuditLogGrpcServerAutoConfiguration}'s javadoc. */
    @Test
    void enabledAlongsideRestServerModuleFailsStartup() {
        contextRunner.withPropertyValues(
                        "audit.log.grpc.enabled=true",
                        "audit.log.multi-tenancy.enabled=true",
                        "audit.log.grpc.api-keys.tenant-a=a-secret",
                        "audit.log.server.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledWithApiKeysAndMultiTenancyStartsTheServer() {
        contextRunner.withPropertyValues(
                        "audit.log.grpc.enabled=true",
                        "audit.log.multi-tenancy.enabled=true",
                        "audit.log.grpc.api-keys.tenant-a=a-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuditLogGrpcServer.class);
                    assertThat(context.getBean(AuditTenantResolver.class)).isInstanceOf(GrpcAuditTenantResolver.class);
                    assertThat(context.getBean(AuditLogGrpcServer.class).getPort()).isGreaterThan(0);
                });
    }
}
