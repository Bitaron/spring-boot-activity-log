package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.annotation.AuditDeliveryMode;
import io.github.bitaron.auditlog.autoconfigure.AuditLogAutoConfiguration;
import io.github.bitaron.auditlog.entity.AuditLog;
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
 * Regression test for WP8: two stacked {@code @Audit} annotations on one method must each fire
 * their own, independent audit dispatch - not just the first, which was the pre-WP8 behavior (see
 * {@link io.github.bitaron.auditlog.annotation.Audits} javadoc history). Goes through the real
 * AspectJ-woven proxy ({@link AopAutoConfiguration}), matching the style of
 * {@link AuditLogAspectTransactionOrderingTest}, rather than invoking the aspect's advice method
 * directly - the pointcut expression itself (matching both the bare {@code @Audit} and the
 * synthetic {@code @Audits} container) is exactly what this regression guards.
 */
class AuditLogAspectTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HostAppMarker.class, RepeatedAuditServiceConfig.class, SyncOverrideServiceConfig.class)
            .withPropertyValues(
                    "spring.datasource.generate-unique-name=true",
                    "spring.jpa.hibernate.ddl-auto=create-drop")
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    DataJpaRepositoriesAutoConfiguration.class,
                    AopAutoConfiguration.class,
                    AuditLogAutoConfiguration.class));

    @Test
    void twoStackedAuditAnnotationsEachDispatchIndependently() {
        contextRunner.run(context -> {
            RepeatedAuditService service = context.getBean(RepeatedAuditService.class);

            service.doThing("payload");

            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(findAll(context)).hasSize(2));

            List<String> auditTypes = findAll(context).stream().map(AuditLog::getAuditType).toList();
            assertThat(auditTypes).containsExactlyInAnyOrder("first", "second");
        });
    }

    /**
     * WP9 acceptance (real-AOP case): the application-wide default is {@code ASYNC} (the
     * autoconfiguration default, left unset here), but this method's {@code @Audit(mode = SYNC)}
     * forces the write onto the caller's thread - so the row must already exist the instant
     * {@code doThingSync()} returns, with no {@link Awaitility} wait needed.
     */
    @Test
    void perCallSyncOverrideIsVisibleImmediatelyThroughRealAop() {
        contextRunner.run(context -> {
            SyncOverrideService service = context.getBean(SyncOverrideService.class);

            service.doThingSync("payload");

            assertThat(findAll(context)).hasSize(1);
            assertThat(findAll(context).get(0).getAuditType()).isEqualTo("sync-override");
        });
    }

    private List<AuditLog> findAll(ApplicationContext context) {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status ->
                context.getBean(EntityManager.class)
                        .createQuery("select a from AuditLog a", AuditLog.class)
                        .getResultList());
    }

    @Configuration(proxyBeanMethods = false)
    static class RepeatedAuditServiceConfig {
        @Bean
        RepeatedAuditService repeatedAuditService() {
            return new RepeatedAuditService();
        }
    }

    static class RepeatedAuditService {
        @Audit(auditType = "first", actionName = "do-thing-first")
        @Audit(auditType = "second", actionName = "do-thing-second")
        public void doThing(String payload) {
            // no-op - the audit records are what this test asserts on
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SyncOverrideServiceConfig {
        @Bean
        SyncOverrideService syncOverrideService() {
            return new SyncOverrideService();
        }
    }

    static class SyncOverrideService {
        @Audit(auditType = "sync-override", mode = AuditDeliveryMode.SYNC)
        public void doThingSync(String payload) {
            // no-op - the audit record is what this test asserts on
        }
    }
}
