package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.autoconfigure.AuditLogAutoConfiguration;
import io.github.bitaron.auditlog.entity.AuditLog;
import io.github.bitaron.auditlog.entity.AuditTemplate;
import io.github.bitaron.auditlog.model.AuditContext;
import io.github.bitaron.auditlog.testfixtures.host.HostAppMarker;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Behavioural tests for the persistence path ({@link AuditLogWriter}), run against a real H2
 * context rather than mocks so the JPA mapping and FreeMarker rendering are exercised for real.
 */
class AuditLogWriterTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            // JpaRepositoriesAutoConfiguration requires AutoConfigurationPackages to be present in
            // any context; HostAppMarker (@AutoConfigurationPackage) stands in for what
            // @SpringBootApplication registers automatically in a real application.
            .withUserConfiguration(HostAppMarker.class)
            .withPropertyValues(
                    "spring.datasource.generate-unique-name=true",
                    "spring.jpa.hibernate.ddl-auto=create-drop",
                    "audit.log.masked-fields=password")
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    JpaRepositoriesAutoConfiguration.class,
                    AuditLogAutoConfiguration.class));

    @Test
    void renderedMessageIsPersisted() {
        contextRunner.run(context -> {
            seedTemplate(context, "greeting", "Hello ${actorName}!");
            persistSynchronously(context, "greeting", clientData("actor-1", "Ada", null));

            List<AuditLog> rows = findAll(context);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getMessage()).isEqualTo("Hello Ada!");
            assertThat(rows.get(0).getActorId()).isEqualTo("actor-1");
        });
    }

    @Test
    void throwingTemplatePropagatesFromWriterButAuditLoggerSwallowsIt() {
        contextRunner.run(context -> {
            seedTemplate(context, "broken", "${nope.");
            AuditContext clientData = clientData("actor-1", "Ada", null);

            // AuditLogWriter itself is allowed to throw - AuditLogger is the layer responsible for
            // isolating that from the caller, so assert that split explicitly.
            assertThatThrownBy(() -> persistSynchronously(context, "broken", clientData));
            assertThat(findAll(context)).isEmpty();

            AuditLogger auditLogger = context.getBean(AuditLogger.class);
            auditLogger.log(fixtureAnnotation("broken"), clientData);
            awaitAsyncCompletion();

            assertThat(findAll(context)).isEmpty(); // still nothing - but no exception reached us.
        });
    }

    @Test
    void missingTemplateIsSkippedNotFatal() {
        contextRunner.run(context -> {
            persistSynchronously(context, "doesNotExist", clientData("actor-1", "Ada", null));
            assertThat(findAll(context)).isEmpty();
        });
    }

    @Test
    void noTemplatesStillRecordsOneRow() {
        contextRunner.run(context -> {
            persistSynchronously(context, "noTemplates", clientData("actor-1", "Ada", null));

            List<AuditLog> rows = findAll(context);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTemplateId()).isNull();
            assertThat(rows.get(0).getMessage()).isNull();
        });
    }

    @Test
    void httpServletRequestArgumentDoesNotBreakSerialization() {
        contextRunner.run(context -> {
            seedTemplate(context, "greeting", "Hello ${actorName}!");
            HttpServletRequest fakeRequest = mock(HttpServletRequest.class);

            persistSynchronously(context, "greeting", clientData("actor-1", "Ada", fakeRequest));

            List<AuditLog> rows = findAll(context);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getData()).doesNotContain("Mockito");
        });
    }

    /**
     * The full-stack regression test for A1, against a real {@link PlatformTransactionManager}
     * and database rather than the mocked version in {@code AuditLoggerTest}: a business
     * operation that calls an audited method and then rolls back its own transaction must not
     * leave behind an audit record describing an operation that never actually happened. Default
     * delivery mode is {@code ASYNC}, exercised here via the real {@link AuditLogger} bean rather
     * than calling {@link AuditLogWriter} directly.
     */
    @Test
    void rolledBackCallerTransactionLeavesNoAuditRowsInAsyncMode() {
        contextRunner.run(context -> {
            seedTemplate(context, "greeting", "Hello ${actorName}!");
            AuditLogger auditLogger = context.getBean(AuditLogger.class);
            AuditContext clientData = clientData("actor-1", "Ada", null);

            transactionTemplate(context).executeWithoutResult(status -> {
                auditLogger.log(fixtureAnnotation("greeting"), clientData);
                status.setRollbackOnly();
            });

            awaitAsyncCompletion();
            assertThat(findAll(context)).isEmpty();
        });
    }

    /**
     * The commit-side counterpart: once the caller's transaction actually commits, the deferred
     * dispatch fires and the record lands.
     */
    @Test
    void committedCallerTransactionEventuallyPersistsInAsyncMode() {
        contextRunner.run(context -> {
            seedTemplate(context, "greeting", "Hello ${actorName}!");
            AuditLogger auditLogger = context.getBean(AuditLogger.class);
            AuditContext clientData = clientData("actor-1", "Ada", null);

            transactionTemplate(context).executeWithoutResult(status ->
                    auditLogger.log(fixtureAnnotation("greeting"), clientData));

            awaitAsyncCompletion();
            assertThat(findAll(context)).hasSize(1);
        });
    }

    /**
     * SYNC mode's whole point: the audit write shares the caller's transaction, so it rolls back
     * with it too, atomically - no waiting for async dispatch needed since nothing was deferred.
     */
    @Test
    void rolledBackCallerTransactionLeavesNoAuditRowsInSyncMode() {
        contextRunner.withPropertyValues("audit.log.mode=SYNC").run(context -> {
            seedTemplate(context, "greeting", "Hello ${actorName}!");
            AuditLogger auditLogger = context.getBean(AuditLogger.class);
            AuditContext clientData = clientData("actor-1", "Ada", null);

            transactionTemplate(context).executeWithoutResult(status -> {
                auditLogger.log(fixtureAnnotation("greeting"), clientData);
                status.setRollbackOnly();
            });

            assertThat(findAll(context)).isEmpty();
        });
    }

    @Test
    void maskedFieldsAreRedacted() {
        contextRunner.run(context -> {
            seedTemplate(context, "greeting", "Hello ${actorName}!");
            record Credentials(String password) {
            }

            persistSynchronously(context, "greeting", clientData("actor-1", "Ada", new Credentials("s3cr3t")));

            String data = findAll(context).get(0).getData();
            assertThat(data).doesNotContain("s3cr3t");
            assertThat(data).contains("\"password\":\"***\"");
        });
    }

    private void seedTemplate(ApplicationContext context, String name, String template) {
        transactionTemplate(context).executeWithoutResult(status -> {
            EntityManager entityManager = context.getBean(EntityManager.class);
            AuditTemplate auditTemplate = new AuditTemplate();
            auditTemplate.setName(name);
            auditTemplate.setTemplate(template);
            entityManager.persist(auditTemplate);
        });
    }

    private void persistSynchronously(ApplicationContext context, String fixtureMethodName, AuditContext clientData) {
        context.getBean(AuditLogWriter.class).persistRequiresNew(fixtureAnnotation(fixtureMethodName), clientData);
    }

    private List<AuditLog> findAll(ApplicationContext context) {
        EntityManager entityManager = context.getBean(EntityManager.class);
        return entityManager.createQuery("select a from AuditLog a", AuditLog.class).getResultList();
    }

    private TransactionTemplate transactionTemplate(ApplicationContext context) {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    private void awaitAsyncCompletion() throws InterruptedException {
        Thread.sleep(500);
    }

    private AuditContext clientData(String actorId, String actorName, Object args) {
        return new AuditContext(actorId, actorName, null, null, null, args, null, null, false);
    }

    /** Retrieves a real {@code @Audit} instance off a fixture method, avoiding hand-rolled annotation proxies. */
    private Audit fixtureAnnotation(String methodName) {
        try {
            Method method = Fixtures.class.getDeclaredMethod(methodName);
            return method.getAnnotation(Audit.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Fixtures {
        @Audit(auditType = "test", actionName = "action", actionType = "type", templates = {"greeting"})
        void greeting() {
        }

        @Audit(auditType = "test", actionName = "action", actionType = "type", templates = {"broken"})
        void broken() {
        }

        @Audit(auditType = "test", actionName = "action", actionType = "type", templates = {"does-not-exist"})
        void doesNotExist() {
        }

        @Audit(auditType = "test", actionName = "action", actionType = "type")
        void noTemplates() {
        }
    }
}
