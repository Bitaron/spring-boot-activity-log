package io.github.bitaron.auditlog.client.autoconfigure;

import io.github.bitaron.auditlog.client.AuditLogHttpClient;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventRequest;
import io.github.bitaron.auditlog.server.proto.v1.AuditEventResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP17 acceptance tests for {@link AuditLogClientAutoConfiguration}'s conditional wiring, plus a
 * proof it produces a working client: the bean it registers is pointed at a real embedded
 * {@code audit-log-spring-boot-server} instance (this test class's own {@code @SpringBootTest})
 * and used to actually ingest an event over HTTP.
 */
@SpringBootTest(
        classes = ClientAutoConfigTestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.generate-unique-name=true",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "audit.log.server.enabled=true",
                "audit.log.multi-tenancy.enabled=true",
                "audit.log.server.api-keys.client-test-tenant=client-test-key"
        })
class AuditLogClientAutoConfigurationTest {

    @LocalServerPort
    private int port;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditLogClientAutoConfiguration.class));

    @Test
    void disabledByDefaultRegistersNoBean() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AuditLogHttpClient.class);
        });
    }

    @Test
    void enabledWithoutBaseUrlFailsStartup() {
        contextRunner.withPropertyValues("audit.log.client.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledWithBaseUrlAndApiKeyRegistersAWorkingClient() {
        contextRunner.withPropertyValues(
                        "audit.log.client.enabled=true",
                        "audit.log.client.base-url=http://localhost:" + port,
                        "audit.log.client.api-key=client-test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuditLogHttpClient.class);

                    AuditLogHttpClient client = context.getBean(AuditLogHttpClient.class);
                    AuditEventResponse response = client.ingest(AuditEventRequest.newBuilder()
                            .setAuditType("client-autoconfig-test")
                            .build());
                    assertThat(response.getAccepted()).isTrue();
                });
    }

    /** A consumer's own {@code AuditLogHttpClient} bean always wins, same as every other
     * {@code @ConditionalOnMissingBean} default in this project. */
    @Test
    void aUserSuppliedClientBeanIsNeverOverridden() {
        contextRunner.withPropertyValues(
                        "audit.log.client.enabled=true",
                        "audit.log.client.base-url=http://localhost:" + port,
                        "audit.log.client.api-key=client-test-key")
                .withUserConfiguration(CustomClientConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AuditLogHttpClient.class))
                            .isSameAs(CustomClientConfig.CUSTOM_CLIENT);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomClientConfig {
        static final AuditLogHttpClient CUSTOM_CLIENT = new AuditLogHttpClient("http://unused", "unused");

        @Bean
        AuditLogHttpClient auditLogHttpClient() {
            return CUSTOM_CLIENT;
        }
    }
}
