package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.autoconfigure.AuditLogAutoConfiguration;
import io.github.bitaron.auditlog.entity.AuditLog;
import io.github.bitaron.auditlog.entity.AuditLogMessage;
import io.github.bitaron.auditlog.entity.AuditOutcome;
import io.github.bitaron.auditlog.testfixtures.host.HostAppMarker;
import jakarta.persistence.EntityManager;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP11 acceptance tests for {@link AuditLogRetentionService}: rows older than the cutoff are
 * deleted in batches (including their child {@link AuditLogMessage} rows), newer rows survive.
 */
class AuditLogRetentionServiceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HostAppMarker.class)
            .withPropertyValues(
                    "spring.datasource.generate-unique-name=true",
                    "spring.jpa.hibernate.ddl-auto=create-drop",
                    "audit.log.retention.enabled=true",
                    "audit.log.retention.max-age=P30D",
                    // Small batch size so the "loop until a batch is short" logic in runOnce() is
                    // actually exercised by seeding more old rows than one batch holds.
                    "audit.log.retention.batch-size=2")
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    JpaRepositoriesAutoConfiguration.class,
                    AuditLogAutoConfiguration.class));

    @Test
    void purgesOnlyRecordsOlderThanTheCutoffIncludingChildMessages() {
        contextRunner.run(context -> {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            // 5 old rows (older than the 30-day cutoff, batch size 2 -> 3 batches) each with one
            // child message, plus 1 recent row that must survive.
            for (int i = 0; i < 5; i++) {
                seedAuditLog(context, now.minusDays(60), true);
            }
            Long survivingId = seedAuditLog(context, now.minusDays(1), true);

            AuditLogRetentionService retentionService = context.getBean(AuditLogRetentionService.class);
            retentionService.runOnce();

            List<AuditLog> remainingLogs = findAllLogs(context);
            List<AuditLogMessage> remainingMessages = findAllMessages(context);

            assertThat(remainingLogs).hasSize(1);
            assertThat(remainingLogs.get(0).getId()).isEqualTo(survivingId);
            assertThat(remainingMessages).hasSize(1);
            assertThat(remainingMessages.get(0).getAuditLogId()).isEqualTo(survivingId);
        });
    }

    /** Seeds one {@link AuditLog} row (and, if requested, one child message) with an explicit
     * {@code createdAt}, bypassing {@link AuditLogWriter} (which always stamps {@code now()}) so
     * the retention cutoff can actually be exercised. Returns the persisted row's id. */
    private Long seedAuditLog(ApplicationContext context, LocalDateTime createdAt, boolean withMessage) {
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        return new TransactionTemplate(transactionManager).execute(status -> {
            EntityManager entityManager = context.getBean(EntityManager.class);
            AuditLog auditLog = new AuditLog();
            auditLog.setAuditType("test");
            auditLog.setCreatedAt(createdAt);
            auditLog.setOutcome(AuditOutcome.SUCCESS);
            entityManager.persist(auditLog);
            if (withMessage) {
                AuditLogMessage message = new AuditLogMessage();
                message.setAuditLogId(auditLog.getId());
                message.setTemplateName("greeting");
                message.setMessage("hi");
                entityManager.persist(message);
            }
            return auditLog.getId();
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
}
