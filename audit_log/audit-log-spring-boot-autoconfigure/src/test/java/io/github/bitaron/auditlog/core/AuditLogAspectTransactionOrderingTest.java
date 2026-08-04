package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.annotation.Audit;
import io.github.bitaron.auditlog.autoconfigure.AuditLogAutoConfiguration;
import io.github.bitaron.auditlog.entity.AuditLog;
import io.github.bitaron.auditlog.entity.AuditOutcome;
import io.github.bitaron.auditlog.entity.AuditTemplate;
import io.github.bitaron.auditlog.testfixtures.host.HostAppMarker;
import io.github.bitaron.auditlog.testfixtures.host.HostEntity;
import io.github.bitaron.auditlog.testfixtures.host.HostRepository;
import jakarta.persistence.EntityManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The end-to-end regression test for {@link AuditLogAspect}'s {@code @Order}: everything else in
 * this test suite exercises the commit-aware dispatch mechanism directly (calling
 * {@link AuditLogger#log} inside a {@code TransactionTemplate}), which proves the mechanism itself
 * works but not that the real AspectJ-woven proxy chain actually invokes it at the right point
 * relative to {@code @Transactional} advice. This test goes through a real
 * {@code @Transactional @Audit} method, proxied by real AOP (hence {@link AopAutoConfiguration}),
 * to prove that in practice too.
 */
class AuditLogAspectTransactionOrderingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HostAppMarker.class, TransactionalAuditedServiceConfig.class)
            .withPropertyValues(
                    "spring.datasource.generate-unique-name=true",
                    "spring.jpa.hibernate.ddl-auto=create-drop")
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    JpaRepositoriesAutoConfiguration.class,
                    AopAutoConfiguration.class,
                    AuditLogAutoConfiguration.class));

    /**
     * A directly {@code @Transactional @Audit} method that writes a {@link HostEntity} row and
     * then throws: the transactional advice must roll that write back. Since
     * {@link AuditLogAspect} is ordered to wrap outside the (default-ordered)
     * {@code @Transactional} advice, this aspect's around-advice only builds and dispatches the
     * audit record once that rollback has already happened - so by the time
     * {@code TransactionSynchronizationManager.isSynchronizationActive()} is checked in
     * {@link AuditLogger}, there is nothing left active for THIS method's own transaction, and
     * ASYNC dispatch fires immediately rather than deferring to a commit that already didn't
     * happen. The resulting audit row correctly describes a failed attempt - not a fabricated
     * success - which is what A1 actually requires: the record must never claim something
     * happened that didn't, not that failed attempts go unrecorded.
     */
    @Test
    void transactionalAuditedMethodRollsBackBusinessDataButStillRecordsTheFailedAttempt() {
        contextRunner.run(context -> {
            seedTemplate(context, "greeting", "Hello ${actorName}!");
            TransactionalAuditedService service = context.getBean(TransactionalAuditedService.class);
            HostRepository hostRepository = context.getBean(HostRepository.class);

            assertThatThrownBy(() -> service.createThenFail("orphaned-row"))
                    .isInstanceOf(IllegalStateException.class);

            // The @Transactional advice rolled this back - real proof the transaction boundary
            // is intact and this aspect isn't accidentally nested inside it in a way that would
            // let business-data writes leak past a rollback.
            assertThat(hostRepository.count()).isZero();

            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(findAll(context)).hasSize(1));

            AuditLog row = findAll(context).get(0);
            assertThat(row.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
        });
    }

    /** The commit-side counterpart, through the same real AOP/transaction stack. */
    @Test
    void transactionalAuditedMethodCommitsBusinessDataAndRecordsSuccess() {
        contextRunner.run(context -> {
            seedTemplate(context, "greeting", "Hello ${actorName}!");
            TransactionalAuditedService service = context.getBean(TransactionalAuditedService.class);
            HostRepository hostRepository = context.getBean(HostRepository.class);

            service.createThenSucceed("kept-row");

            assertThat(hostRepository.count()).isEqualTo(1);
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(findAll(context)).hasSize(1));
            assertThat(findAll(context).get(0).getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        });
    }

    private void seedTemplate(ApplicationContext context, String name, String template) {
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            EntityManager entityManager = context.getBean(EntityManager.class);
            AuditTemplate auditTemplate = new AuditTemplate();
            auditTemplate.setName(name);
            auditTemplate.setTemplate(template);
            entityManager.persist(auditTemplate);
        });
    }

    private List<AuditLog> findAll(ApplicationContext context) {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status ->
                context.getBean(EntityManager.class)
                        .createQuery("select a from AuditLog a", AuditLog.class)
                        .getResultList());
    }

    @Configuration(proxyBeanMethods = false)
    static class TransactionalAuditedServiceConfig {
        @Bean
        TransactionalAuditedService transactionalAuditedService(HostRepository hostRepository) {
            return new TransactionalAuditedService(hostRepository);
        }
    }

    static class TransactionalAuditedService {
        private final HostRepository hostRepository;

        TransactionalAuditedService(HostRepository hostRepository) {
            this.hostRepository = hostRepository;
        }

        @Transactional
        @Audit(auditType = "test", actionName = "create-then-fail", templates = {"greeting"})
        public void createThenFail(String name) {
            HostEntity entity = new HostEntity();
            entity.setName(name);
            hostRepository.save(entity);
            throw new IllegalStateException("simulated failure after write");
        }

        @Transactional
        @Audit(auditType = "test", actionName = "create-then-succeed", templates = {"greeting"})
        public void createThenSucceed(String name) {
            HostEntity entity = new HostEntity();
            entity.setName(name);
            hostRepository.save(entity);
        }
    }
}
