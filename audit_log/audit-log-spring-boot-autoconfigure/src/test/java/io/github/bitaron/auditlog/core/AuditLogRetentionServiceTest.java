package io.github.bitaron.auditlog.core;

import io.github.bitaron.auditlog.autoconfigure.AuditLogAutoConfiguration;
import io.github.bitaron.auditlog.entity.AuditLog;
import io.github.bitaron.auditlog.entity.AuditLogMessage;
import io.github.bitaron.auditlog.entity.AuditOutcome;
import io.github.bitaron.auditlog.testfixtures.host.HostAppMarker;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
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
                    DataJpaRepositoriesAutoConfiguration.class,
                    AuditLogAutoConfiguration.class));

    @Test
    void purgesOnlyRecordsOlderThanTheCutoffIncludingChildMessages() {
        contextRunner.run(context -> {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            // 5 old rows (older than the 30-day cutoff, batch size 2 -> 3 batches) each with one
            // child message, plus 1 recent row that must survive.
            for (int i = 0; i < 5; i++) {
                seedAuditLog(context, now.minusDays(60), true, null);
            }
            Long survivingId = seedAuditLog(context, now.minusDays(1), true, null);

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

    /**
     * WP16 acceptance: a tenant with a shorter {@code retention.tenant-max-age} override is purged
     * to its own, tighter cutoff, while another tenant (and the no-tenant/legacy case) keeps
     * following the global {@code retention.max-age} - one tenant's retention policy never
     * over- or under-purges another's data.
     */
    @Test
    void perTenantMaxAgeOverridesOnlyPurgeThatTenantToItsOwnCutoff() {
        contextRunner.withPropertyValues("audit.log.retention.tenant-max-age.short-lived=P5D")
                .run(context -> {
                    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                    // 10 days old: older than short-lived's 5-day override (purged), but newer than
                    // the global 30-day default (survives for every other tenant/no-tenant).
                    Long shortLivedId = seedAuditLog(context, now.minusDays(10), false, "short-lived");
                    Long longLivedId = seedAuditLog(context, now.minusDays(10), false, "long-lived");
                    Long noTenantId = seedAuditLog(context, now.minusDays(10), false, null);

                    context.getBean(AuditLogRetentionService.class).runOnce();

                    List<Long> remainingIds = findAllLogs(context).stream().map(AuditLog::getId).toList();
                    assertThat(remainingIds).doesNotContain(shortLivedId);
                    assertThat(remainingIds).contains(longLivedId, noTenantId);
                });
    }

    /** Seeds one {@link AuditLog} row (and, if requested, one child message) with an explicit
     * {@code createdAt} and tenant, bypassing {@link AuditLogWriter} (which always stamps
     * {@code now()}) so the retention cutoff can actually be exercised. Returns the persisted
     * row's id. */
    private Long seedAuditLog(ApplicationContext context, LocalDateTime createdAt, boolean withMessage, String tenantId) {
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        return new TransactionTemplate(transactionManager).execute(status -> {
            EntityManager entityManager = context.getBean(EntityManager.class);
            AuditLog auditLog = new AuditLog();
            auditLog.setAuditType("test");
            auditLog.setCreatedAt(createdAt);
            auditLog.setOutcome(AuditOutcome.SUCCESS);
            auditLog.setTenantId(tenantId);
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
