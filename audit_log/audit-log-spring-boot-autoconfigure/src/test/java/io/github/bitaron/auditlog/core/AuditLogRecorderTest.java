package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.autoconfigure.AuditLogAutoConfiguration;
import io.github.bitaron.auditlog.contract.AuditLogRecorder;
import io.github.bitaron.auditlog.entity.AuditLog;
import io.github.bitaron.auditlog.entity.AuditLogMessage;
import io.github.bitaron.auditlog.model.AuditEventRequest;
import io.github.bitaron.auditlog.testfixtures.host.HostAppMarker;
import jakarta.persistence.EntityManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP12 acceptance test: {@link AuditLogRecorder#record} must produce the same
 * {@link AuditLog}/{@link AuditLogMessage} shape as an equivalent {@code @Audit}-annotated call -
 * proving the programmatic path reuses {@link AuditLogWriter}/{@link AuditLogger} rather than
 * diverging from it.
 */
class AuditLogRecorderTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HostAppMarker.class, AnnotatedServiceConfig.class)
            .withPropertyValues(
                    "spring.datasource.generate-unique-name=true",
                    "spring.jpa.hibernate.ddl-auto=create-drop",
                    "audit.log.templates.greeting=Hello ${actorName}!")
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    DataJpaRepositoriesAutoConfiguration.class,
                    AopAutoConfiguration.class,
                    AuditLogAutoConfiguration.class));

    @Test
    void recordProducesTheSameShapeAsAnEquivalentAnnotatedCall() {
        contextRunner.run(context -> {
            AnnotatedService service = context.getBean(AnnotatedService.class);
            service.greet("Ada");
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(findAllLogs(context)).hasSize(1));
            AuditLog viaAop = findAllLogs(context).get(0);
            AuditLogMessage viaAopMessage = findAllMessages(context).get(0);

            AuditLogRecorder recorder = context.getBean(AuditLogRecorder.class);
            recorder.record(new AuditEventRequest(
                    "greeting-event", "greet", "", "", List.of("greeting"),
                    null, "Ada", null, null, null, null, null, null, false, 0, null, null));
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(findAllLogs(context)).hasSize(2));

            AuditLog viaRecorder = findAllLogs(context).stream()
                    .filter(row -> !row.getId().equals(viaAop.getId()))
                    .findFirst().orElseThrow();
            AuditLogMessage viaRecorderMessage = findAllMessages(context).stream()
                    .filter(m -> !m.getId().equals(viaAopMessage.getId()))
                    .findFirst().orElseThrow();

            // Same shape: one AuditLog row, one child AuditLogMessage row, template rendered
            // identically off the same actorName - auditType differs deliberately (each path
            // used its own), everything else lines up.
            assertThat(viaRecorder.getOutcome()).isEqualTo(viaAop.getOutcome());
            assertThat(viaRecorder.getActorName()).isEqualTo(viaAop.getActorName());
            assertThat(viaRecorderMessage.getMessage()).isEqualTo(viaAopMessage.getMessage());
            assertThat(viaRecorderMessage.getTemplateName()).isEqualTo(viaAopMessage.getTemplateName());
        });
    }

    private List<AuditLog> findAllLogs(ApplicationContext context) {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status ->
                context.getBean(EntityManager.class)
                        .createQuery("select a from AuditLog a", AuditLog.class)
                        .getResultList());
    }

    private List<AuditLogMessage> findAllMessages(ApplicationContext context) {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status ->
                context.getBean(EntityManager.class)
                        .createQuery("select m from AuditLogMessage m", AuditLogMessage.class)
                        .getResultList());
    }

    @Configuration(proxyBeanMethods = false)
    static class AnnotatedServiceConfig {
        @Bean
        AnnotatedService annotatedService() {
            return new AnnotatedService();
        }
    }

    static class AnnotatedService {
        @Audit(auditType = "greeting-annotated", actionName = "greet", templates = {"greeting"},
                actorSource = io.github.bitaron.auditlog.annotation.ActorSource.EXPRESSION,
                actorExpression = "'Ada'")
        public void greet(String name) {
            // no-op
        }
    }
}
